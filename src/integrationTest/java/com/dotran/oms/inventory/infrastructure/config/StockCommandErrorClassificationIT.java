package com.dotran.oms.inventory.infrastructure.config;

import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.service.ConfirmStockReservationService;
import com.dotran.oms.inventory.application.service.ReserveStockService;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.StockCommands;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * The timeouts of a stock command, produced by a real Postgres and given to the real Kafka error handler: each one is
 * retried a bounded number of times and then dead-lettered, never dead-lettered at once (contract error) and never
 * retried without limit (connection failure). See oms-workspace flows/order-stock-reservation.md, Timeouts.
 * No Kafka here: the handler is called the way the listener container calls it.
 */
@DisplayName("Stock command timeouts vs the Kafka error handler (Postgres)")
class StockCommandErrorClassificationIT extends PostgresIT {

    @Autowired
    private ReserveStockService reserveService;
    @Autowired
    private ConfirmStockReservationService confirmService;
    @Autowired
    private ReservationProperties properties;
    @Autowired
    private MdcPropagation mdcPropagation;

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final StockCommands commands = new StockCommands(merchant, store, product);

    private ExecutorService pool;
    private Duration lockTimeout;
    private Duration statementTimeout;
    private UUID skuA;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();
        lockTimeout = properties.getLockTimeout();
        statementTimeout = properties.getStatementTimeout();
        skuA = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // The context (and so the properties bean) is shared with the other IT classes.
        properties.setLockTimeout(lockTimeout);
        properties.setStatementTimeout(statementTimeout);
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Runs {@code sql} (a statement that takes a lock) in another session and keeps its transaction open. */
    private Future<?> hold(String sql, CountDownLatch locked, CountDownLatch release, Object... args) {
        return pool.submit(() -> tx.executeWithoutResult(st -> {
            if (args.length == 0) {
                jdbc.execute(sql);
            } else {
                jdbc.queryForList(sql, args);
            }
            locked.countDown();
            try {
                // Bounded: a lock left behind would block the next test's TRUNCATE.
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    /** The failure of {@code command} while another session holds the lock taken by {@code sql}. */
    private Throwable failureWhileHolding(String sql, Runnable command, Object... args) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Future<?> holder = hold(sql, locked, done, args);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            return catchThrowable(command::run);
        } finally {
            done.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /** Not a connection failure, retried MAX_RETRIES times, then sent to the dead-letter topic. */
    private void assertBoundedRetriesThenDeadLetter(Throwable failure) {
        // Wrapped like the listener container does, so classification has to look at the cause chain.
        Exception wrapped = new ListenerExecutionFailedException("listener failed", failure);
        assertThat(KafkaErrorHandlingConfig.isConnectionFailure(wrapped)).as("connection failure: %s", failure).isFalse();

        AtomicInteger deadLettered = new AtomicInteger();
        ExponentialBackOffWithMaxRetries backOff = KafkaErrorHandlingConfig.backOff();
        backOff.setInitialInterval(1L);
        backOff.setMaxInterval(1L);
        DefaultErrorHandler handler = KafkaErrorHandlingConfig.errorHandler(
                (record, exception) -> deadLettered.incrementAndGet(), backOff, mdcPropagation);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("ConfirmStockReservation", 0, 7L, "key", "value");
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        for (int attempt = 1; attempt <= KafkaErrorHandlingConfig.MAX_RETRIES; attempt++) {
            assertThat(handler.handleOne(wrapped, record, consumer, container)).as("attempt %d", attempt).isFalse();
        }
        assertThat(deadLettered).as("dead-lettered while retries are left").hasValue(0);
        assertThat(handler.handleOne(wrapped, record, consumer, container)).isTrue();
        assertThat(deadLettered).hasValue(1);
    }

    @Test
    @DisplayName("lock_timeout on the order header (55P03): bounded retries, then DLT")
    void lockTimeout() throws Exception {
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        properties.setLockTimeout(Duration.ofMillis(300));

        Throwable failure = failureWhileHolding("SELECT 1 FROM stock_reservation_orders WHERE order_id = ? FOR UPDATE",
                () -> confirmService.confirm(commands.confirm(order)), order);

        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(sqlState(failure)).isEqualTo("55P03");
        assertBoundedRetriesThenDeadLetter(failure);
    }

    @Test
    @DisplayName("statement_timeout under Hibernate (57014): bounded retries, then DLT")
    void statementTimeoutOnHeaderLock() throws Exception {
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        // lock_timeout above statement_timeout (never valid in configuration) so the wait ends by statement_timeout.
        properties.setLockTimeout(Duration.ofSeconds(5));
        properties.setStatementTimeout(Duration.ofMillis(300));

        Throwable failure = failureWhileHolding("SELECT 1 FROM stock_reservation_orders WHERE order_id = ? FOR UPDATE",
                () -> confirmService.confirm(commands.confirm(order)), order);

        assertThat(failure).isInstanceOf(QueryTimeoutException.class);
        assertThat(sqlState(failure)).isEqualTo("57014");
        assertBoundedRetriesThenDeadLetter(failure);
    }

    @Test
    @DisplayName("statement_timeout on the outbox INSERT (plain JDBC, 57014): bounded retries, then DLT; nothing committed")
    void statementTimeoutOnOutboxInsert() throws Exception {
        UUID order = UUID.randomUUID();
        properties.setLockTimeout(Duration.ofSeconds(5));
        properties.setStatementTimeout(Duration.ofMillis(300));

        // The reserve does all its work, then waits at the outbox INSERT until statement_timeout cancels it.
        Throwable failure = failureWhileHolding("LOCK TABLE inventory.outbox_events IN ACCESS EXCLUSIVE MODE",
                () -> reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 2))));

        // Spring JDBC (not Hibernate) translates this one: SQLState class 57 is a "resource failure" there.
        assertThat(failure).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(sqlState(failure)).isEqualTo("57014");
        assertBoundedRetriesThenDeadLetter(failure);
        assertThat(count("SELECT count(*) FROM stock_reservation_orders WHERE order_id = ?", order)).isZero();
        assertThat(count("SELECT count(*) FROM stock_reservations")).isZero();
        assertThat(count("SELECT count(*) FROM stock_movements")).isZero();
        assertThat(reserved(skuA)).isZero();
    }

    @Test
    @DisplayName("a statement started after the transaction deadline: bounded retries, then DLT")
    void transactionDeadline() {
        TransactionTemplate shortTx = new TransactionTemplate(tx.getTransactionManager());
        shortTx.setTimeout(1);

        Throwable failure = catchThrowable(() -> shortTx.executeWithoutResult(st -> {
            try {
                // A sleep on purpose (the passing of time is what is tested): past the 1 s deadline.
                Thread.sleep(1_200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.queryForList("SELECT 1 FROM stock_reservation_orders");
        }));

        assertThat(failure).isInstanceOf(TransactionTimedOutException.class);
        assertBoundedRetriesThenDeadLetter(failure);
    }
}
