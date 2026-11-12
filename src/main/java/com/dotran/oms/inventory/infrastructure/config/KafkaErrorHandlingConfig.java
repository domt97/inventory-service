package com.dotran.oms.inventory.infrastructure.config;

import com.dotran.oms.core.logging.LogSanitizer;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.inventory.infrastructure.message.KafkaMdc;
import com.dotran.oms.inventory.domain.exception.CatalogOwnershipMismatchException;
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.sql.SQLException;

/**
 * Error handling for every Kafka listener (Boot wires a single CommonErrorHandler bean into the listener container
 * factory). Contract errors go to {@code <topic>.DLT} at once. Database connection failures are retried without limit
 * (backoff up to 1 min), so an outage does not dead-letter catalog events or stock commands. Other failures (also the
 * timeouts of a stock command, oms-workspace flows/order-stock-reservation.md) are retried 6 times with backoff, then
 * sent to the DLT. If publishing to the DLT fails, the record is retried, not lost.
 */
@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";
    static final int MAX_RETRIES = 6;
    static final long CONNECTION_FAILURE_MAX_INTERVAL_MS = 60_000L;
    private static final String QUERY_CANCELED = "57014";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafkaTemplate, MdcPropagation mdcPropagation) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                // partition -1: let Kafka pick one, the DLT may have fewer partitions than the source topic
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        return errorHandler(deadLetter, backOff(), mdcPropagation);
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer deadLetter, ExponentialBackOffWithMaxRetries backOff,
                                            MdcPropagation mdcPropagation) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            // Runs on the listener thread after the listener's own scope closed: reopen it for the DLT line.
            try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
                log.error("Sending {} record key={} partition={} offset={} to {}{}: {}", record.topic(),
                        // The key is caller-chosen text, like the message.
                        record.key() == null ? null : LogSanitizer.escape(String.valueOf(record.key())),
                        record.partition(), record.offset(), record.topic(), DLT_SUFFIX,
                        LogSanitizer.escape(exception.getMessage()), exception);
                deadLetter.accept(record, exception);
            }
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setBackOffFunction((record, exception) -> isConnectionFailure(exception) ? connectionFailureBackOff() : null);
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                InvalidCatalogEventException.class,
                CatalogOwnershipMismatchException.class,
                InvalidStockCommandException.class,
                StockCommandOwnershipMismatchException.class,
                DataIntegrityViolationException.class);
        return handler;
    }

    /**
     * The database is unreachable: waiting is the only fix, so retry until it is back. A cancelled statement
     * (SQLState 57014: statement_timeout or a JDBC query timeout) is never one, although Spring JDBC translates the
     * whole SQLState class 57 to DataAccessResourceFailureException (seen on the outbox INSERT, which is plain JDBC):
     * a timeout gets the bounded retries.
     */
    static boolean isConnectionFailure(Throwable exception) {
        boolean connectionFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return false;
            }
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotCreateTransactionException) {
                connectionFailure = true;
            }
        }
        return connectionFailure;
    }

    static ExponentialBackOff connectionFailureBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(CONNECTION_FAILURE_MAX_INTERVAL_MS);
        return backOff;
    }

    static ExponentialBackOffWithMaxRetries backOff() {
        // 1 + 2 + 4 + 8 + 10 + 10 s ≈ 35 s, well under max.poll.interval.ms (300 s)
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        return backOff;
    }

    @Bean
    public NewTopic productCreatedDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return TopicBuilder.name("ProductCreated" + DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }

    @Bean
    public NewTopic productUpdatedDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return TopicBuilder.name("ProductUpdated" + DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }

    @Bean
    public NewTopic reserveStockDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return TopicBuilder.name("ReserveStock" + DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }

    @Bean
    public NewTopic confirmStockReservationDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return TopicBuilder.name("ConfirmStockReservation" + DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }

    @Bean
    public NewTopic releaseStockReservationDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return TopicBuilder.name("ReleaseStockReservation" + DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }
}
