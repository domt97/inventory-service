package com.dotran.oms.inventory.infrastructure.config;

import com.dotran.oms.inventory.domain.exception.CatalogOwnershipMismatchException;
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
import com.fasterxml.jackson.core.JsonParseException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaErrorHandlingConfig")
class KafkaErrorHandlingConfigTest {

    @Mock private ConsumerRecordRecoverer deadLetter;
    @Mock private Consumer<?, ?> consumer;
    @Mock private MessageListenerContainer container;

    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("ProductUpdated", 0, 42L, "key", "value");

    private DefaultErrorHandler handler() {
        ExponentialBackOffWithMaxRetries backOff = KafkaErrorHandlingConfig.backOff();
        backOff.setInitialInterval(1L);
        backOff.setMaxInterval(1L);
        return KafkaErrorHandlingConfig.errorHandler(deadLetter, backOff);
    }

    /** Wraps like the listener container does, so classification has to look at the cause. */
    private static ListenerExecutionFailedException wrapped(Exception cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }

    @Test
    @DisplayName("contract errors go to the dead-letter topic on the first failure")
    void contractErrors_recoveredImmediately() {
        for (Exception cause : new Exception[]{
                new JsonParseException(null, "bad json"),
                new InvalidCatalogEventException("bad event"),
                new CatalogOwnershipMismatchException("other merchant"),
                new DataIntegrityViolationException("constraint")}) {
            reset(deadLetter);

            boolean handled = handler().handleOne(wrapped(cause), record, consumer, container);

            assertThat(handled).as(cause.getClass().getSimpleName()).isTrue();
            verify(deadLetter).accept(eq(record), any());
        }
    }

    @Test
    @DisplayName("a transient failure (lock timeout, deadlock) is retried, not sent to the dead-letter topic")
    void transientError_isRetried() {
        boolean handled = handler().handleOne(wrapped(new CannotAcquireLockException("deadlock")), record, consumer, container);

        assertThat(handled).isFalse();
        verify(deadLetter, never()).accept(any(), any());
    }

    @Test
    @DisplayName("a transient failure goes to the dead-letter topic once the 6 retries are used up")
    void transientError_recoveredAfterRetries() {
        DefaultErrorHandler handler = handler();
        Exception failure = wrapped(new CannotAcquireLockException("deadlock"));

        for (int attempt = 1; attempt <= KafkaErrorHandlingConfig.MAX_RETRIES; attempt++) {
            assertThat(handler.handleOne(failure, record, consumer, container)).as("attempt %d", attempt).isFalse();
        }
        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
        verify(deadLetter).accept(eq(record), any());
    }

    @Test
    @DisplayName("a database connection failure is retried without limit (never sent to the dead-letter topic)")
    void connectionFailure_neverRecovered() {
        DefaultErrorHandler handler = KafkaErrorHandlingConfig.errorHandler(deadLetter, KafkaErrorHandlingConfig.backOff());
        handler.setBackOffFunction((r, e) -> KafkaErrorHandlingConfig.isConnectionFailure(e) ? new FixedBackOff(0L, FixedBackOff.UNLIMITED_ATTEMPTS) : null);
        Exception failure = wrapped(new CannotCreateTransactionException("db down", new CannotGetJdbcConnectionException("refused")));

        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
        }
        verify(deadLetter, never()).accept(any(), any());
    }

    @Test
    @DisplayName("connection failures are recognised through the cause chain; other errors are not")
    void connectionFailureClassification() {
        assertThat(KafkaErrorHandlingConfig.isConnectionFailure(
                wrapped(new CannotCreateTransactionException("db down")))).isTrue();
        assertThat(KafkaErrorHandlingConfig.isConnectionFailure(
                wrapped(new RuntimeException(new CannotGetJdbcConnectionException("refused"))))).isTrue();
        assertThat(KafkaErrorHandlingConfig.isConnectionFailure(
                wrapped(new CannotAcquireLockException("deadlock")))).isFalse();
        assertThat(KafkaErrorHandlingConfig.connectionFailureBackOff().getMaxInterval())
                .isEqualTo(KafkaErrorHandlingConfig.CONNECTION_FAILURE_MAX_INTERVAL_MS);
    }

    @Test
    @DisplayName("backoff: 6 retries, 1 s doubling up to 10 s")
    void backOffSettings() {
        ExponentialBackOffWithMaxRetries backOff = KafkaErrorHandlingConfig.backOff();

        assertThat(backOff.getMaxRetries()).isEqualTo(6);
        assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(10_000L);
    }
}
