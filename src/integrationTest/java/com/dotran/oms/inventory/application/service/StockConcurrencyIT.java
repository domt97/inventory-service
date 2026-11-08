package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.application.repository.ExpiryCursor;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.StockCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Races of the stock commands on Postgres: latches start the threads together, every future is awaited with a
 * timeout (a deadlock or a hang fails the test), and the invariants are checked on the committed rows.
 */
@DisplayName("Stock command concurrency (Postgres)")
class StockConcurrencyIT extends PostgresIT {

    private static final int THREADS = 8;

    @Autowired
    private ReserveStockService reserveService;
    @Autowired
    private ConfirmStockReservationService confirmService;
    @Autowired
    private ReleaseStockReservationService releaseService;
    @Autowired
    private ExpireByInventoryTransaction expireTransaction;
    @Autowired
    private StockReservationRepository reservationRepository;
    @Autowired
    private StockReservationOrderRepository headerRepository;
    @Autowired
    private ReservationProperties properties;

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final StockCommands commands = new StockCommands(merchant, store, product);

    private ExecutorService pool;
    private Duration lockTimeout;
    private Duration statementTimeout;

    @BeforeEach
    void setUp() {
        // One thread more than the racing commands, so the expiry task starts together with them.
        pool = Executors.newFixedThreadPool(THREADS + 1);
        lockTimeout = properties.getLockTimeout();
        statementTimeout = properties.getStatementTimeout();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        properties.setLockTimeout(lockTimeout);
        properties.setStatementTimeout(statementTimeout);
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** Runs the tasks together and returns each outcome: the value, or the exception thrown. */
    private List<Object> race(List<Callable<?>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void assertNoDeadlock(List<Object> outcomes) {
        for (Object outcome : outcomes) {
            if (outcome instanceof Throwable t) {
                assertThat(sqlState(t)).as("deadlock in %s", t).isNotEqualTo("40P01");
            }
        }
    }

    /** One reserve on its own row before a race, so a cold JVM does not stretch the first lock waits. */
    private void warmUp() {
        insertInventory(merchant, store, product, "SKU-WARMUP", 1, 0, "ACTIVE");
        reserveService.reserve(commands.reserve(UUID.randomUUID(), commands.line(UUID.randomUUID(), "SKU-WARMUP", 1)));
    }

    /** Holds the order header FOR UPDATE in another thread until {@code release} is counted down. */
    private Future<?> holdHeader(UUID order, CountDownLatch locked, CountDownLatch release) {
        return pool.submit(() -> tx.executeWithoutResult(st -> {
            headerRepository.lockForUpdate(OrderId.of(order), MerchantId.of(merchant), StoreId.of(store));
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    @Test
    @DisplayName("the same ReserveStock delivered twice at once holds the stock once")
    void sameOrderTwice() throws Exception {
        UUID inventory = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        var reserve = commands.reserve(UUID.randomUUID(), commands.line(UUID.randomUUID(), "SKU-A", 4));

        List<Object> outcomes = race(List.of(() -> reserveService.reserve(reserve), () -> reserveService.reserve(reserve)));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(ReserveStockResult.Reserved.class));
        assertThat(reserved(inventory)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM stock_reservations")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type = 'RESERVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("orders racing for the same SKU never oversell")
    void noOversell() throws Exception {
        warmUp();
        UUID inventory = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            var reserve = commands.reserve(UUID.randomUUID(), commands.line(UUID.randomUUID(), "SKU-A", 3));
            tasks.add(() -> reserveService.reserve(reserve));
        }

        List<Object> outcomes = race(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(ReserveStockResult.class));
        long reservedOrders = outcomes.stream().filter(ReserveStockResult.Reserved.class::isInstance).count();
        assertThat(reservedOrders).isEqualTo(3);
        assertThat(reserved(inventory)).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM stock_reservation_orders WHERE status = 'REJECTED'"))
                .isEqualTo(THREADS - 3);
    }

    @Test
    @DisplayName("orders locking two SKUs in opposite command order do not deadlock")
    void oppositeLineOrder() throws Exception {
        warmUp();
        UUID a = insertInventory(merchant, store, product, "SKU-A", 100, 0, "ACTIVE");
        UUID b = insertInventory(merchant, store, product, "SKU-B", 100, 0, "ACTIVE");
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            var lineA = commands.line(UUID.randomUUID(), "SKU-A", 1);
            var lineB = commands.line(UUID.randomUUID(), "SKU-B", 1);
            var reserve = i % 2 == 0 ? commands.reserve(UUID.randomUUID(), lineA, lineB)
                    : commands.reserve(UUID.randomUUID(), lineB, lineA);
            tasks.add(() -> reserveService.reserve(reserve));
        }

        List<Object> outcomes = race(tasks);

        assertNoDeadlock(outcomes);
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(ReserveStockResult.Reserved.class));
        assertThat(reserved(a)).isEqualTo(THREADS);
        assertThat(reserved(b)).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("a command waiting longer than lock_timeout on the order header: PessimisticLockingFailureException (55P03), nothing written")
    void lockTimeout() throws Exception {
        insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        properties.setLockTimeout(Duration.ofMillis(300));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Future<?> holder = holdHeader(order, locked, done);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            // The error classification of the command consumers (retry vs DLT) relies on this exception type.
            assertThatThrownBy(() -> releaseService.release(commands.release(order, false)))
                    .isInstanceOf(PessimisticLockingFailureException.class)
                    .satisfies(e -> assertThat(sqlState(e)).as("exception %s", e).isEqualTo("55P03"));
        } finally {
            done.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservation_orders WHERE order_id = ?",
                String.class, order)).isEqualTo("RESERVED");
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type = 'RESERVE_RELEASE'")).isZero();
    }

    @Test
    @DisplayName("a statement running past statement_timeout: Spring QueryTimeoutException (57014), nothing written")
    void statementTimeout() throws Exception {
        insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        // lock_timeout above statement_timeout (never valid in configuration) so the wait ends by statement_timeout.
        properties.setLockTimeout(Duration.ofSeconds(5));
        properties.setStatementTimeout(Duration.ofMillis(300));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Future<?> holder = holdHeader(order, locked, done);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> confirmService.confirm(commands.confirm(order)))
                    .isInstanceOf(QueryTimeoutException.class)
                    .satisfies(e -> assertThat(sqlState(e)).as("exception %s", e).isEqualTo("57014"));
        } finally {
            done.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type = 'SALE'")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservation_orders WHERE order_id = ?",
                String.class, order)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("a statement started after the transaction deadline: TransactionTimedOutException")
    void transactionDeadline() {
        TransactionTemplate shortTx = new TransactionTemplate(tx.getTransactionManager());
        shortTx.setTimeout(1);

        assertThatThrownBy(() -> shortTx.executeWithoutResult(st -> {
            try {
                // A sleep on purpose (the passing of time is what is tested): past the 1 s deadline.
                Thread.sleep(1_200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            headerRepository.lockForUpdate(OrderId.of(UUID.randomUUID()), MerchantId.of(merchant), StoreId.of(store));
        })).isInstanceOf(TransactionTimedOutException.class);
    }

    @Test
    @DisplayName("confirm and release racing the expiry: every hold ends exactly once, reserved quantity back to 0")
    void confirmAndReleaseAgainstExpiry() throws Exception {
        UUID inventory = insertInventory(merchant, store, product, "SKU-A", 100, 0, "ACTIVE");
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID order = UUID.randomUUID();
            orders.add(order);
            reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 2)));
        }
        jdbc.update("UPDATE stock_reservations SET expires_at = now() - interval '1 minute'");
        List<StockReservation> due = tx.execute(st -> reservationRepository.getExpiredReservations(Instant.now(),
                ExpiryCursor.start(), 100));
        assertThat(due).hasSize(THREADS);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            UUID order = orders.get(i);
            tasks.add(i % 2 == 0 ? () -> confirmService.confirm(commands.confirm(order))
                    : () -> releaseService.release(commands.release(order, false)));
        }
        tasks.add(() -> {
            expireTransaction.execute(InventoryId.of(inventory), due);
            return "expired";
        });

        List<Object> outcomes = race(tasks);

        assertNoDeadlock(outcomes);
        assertThat(reserved(inventory)).isZero();
        int confirmed = count("SELECT count(*) FROM stock_reservations WHERE status = 'CONFIRMED'");
        int released = count("SELECT count(*) FROM stock_reservations WHERE status = 'RELEASED'");
        int expired = count("SELECT count(*) FROM stock_reservations WHERE status = 'EXPIRED'");
        assertThat(confirmed + released + expired).isEqualTo(THREADS);
        assertThat(quantity(inventory)).isEqualTo(100 - 2L * confirmed);
        // One movement per final transition, never two for the same hold.
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type = 'SALE'")).isEqualTo(confirmed);
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type = 'RESERVE_RELEASE'")).isEqualTo(released);
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type = 'RESERVE_EXPIRE'")).isEqualTo(expired);
        // A confirm that lost to the expiry threw (rolled back) or answered NOT_RESERVED; never Confirmed.
        for (int i = 0; i < orders.size(); i += 2) {
            Object outcome = outcomes.get(i);
            String status = jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_id = ?",
                    String.class, orders.get(i));
            if (outcome instanceof ConfirmStockReservationResult.Confirmed) {
                assertThat(status).isEqualTo("CONFIRMED");
            } else {
                assertThat(status).isEqualTo("EXPIRED");
            }
        }
    }
}
