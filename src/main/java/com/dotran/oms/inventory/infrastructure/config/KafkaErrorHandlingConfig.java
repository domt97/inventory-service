package com.dotran.oms.inventory.infrastructure.config;

import com.dotran.oms.inventory.domain.exception.CatalogOwnershipMismatchException;
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
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

/**
 * Error handling for every Kafka listener (Boot wires a single CommonErrorHandler bean into the listener container
 * factory). Contract errors go to {@code <topic>.DLT} at once. Database connection failures are retried without limit
 * (backoff up to 1 min), so an outage does not dead-letter catalog events. Other failures are retried 6 times with
 * backoff, then sent to the DLT. If publishing to the DLT fails, the record is retried, not lost.
 */
@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";
    static final int MAX_RETRIES = 6;
    static final long CONNECTION_FAILURE_MAX_INTERVAL_MS = 60_000L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                // partition -1: let Kafka pick one, the DLT may have fewer partitions than the source topic
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        return errorHandler(deadLetter, backOff());
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer deadLetter, ExponentialBackOffWithMaxRetries backOff) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            log.error("Sending {} record key={} offset={} to {}{}: {}", record.topic(), record.key(), record.offset(),
                    record.topic(), DLT_SUFFIX, exception.getMessage(), exception);
            deadLetter.accept(record, exception);
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setBackOffFunction((record, exception) -> isConnectionFailure(exception) ? connectionFailureBackOff() : null);
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                InvalidCatalogEventException.class,
                CatalogOwnershipMismatchException.class,
                DataIntegrityViolationException.class);
        return handler;
    }

    /** The database is unreachable: waiting is the only fix, so retry until it is back. */
    static boolean isConnectionFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
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
}
