package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.config.ReservationExpiryProperties;
import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.handler.annotation.Header;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SqsReservationExpireConsumer")
class SqsReservationExpireConsumerTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Mock
    private ExpireStockReservationUseCase expireStockReservationUseCase;

    @Mock
    private Clock clock;

    private SqsReservationExpireConsumer consumer;

    @BeforeEach
    void setUp() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setMaxTickAge(Duration.ofMinutes(2));
        consumer = new SqsReservationExpireConsumer(expireStockReservationUseCase, clock, properties);
        lenient().when(clock.instant()).thenReturn(NOW);
    }

    private static String sentAgo(Duration age) {
        return String.valueOf(NOW.minus(age).toEpochMilli());
    }

    /** Runs one fresh tick so that this instance has swept recently. */
    private void sweepRecently() {
        consumer.consume("tick", sentAgo(Duration.ofSeconds(5)));
        clearInvocations(expireStockReservationUseCase);
    }

    @Test
    @DisplayName("runs the sweep for a fresh tick")
    void shouldRunForFreshTick() {
        consumer.consume("tick", sentAgo(Duration.ofSeconds(10)));

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("drops a stale tick when this instance swept recently, returning normally so it is acknowledged")
    void shouldDropStaleTickAfterRecentSweep() {
        sweepRecently();

        assertDoesNotThrow(() -> consumer.consume("tick", sentAgo(Duration.ofMinutes(5))));

        verify(expireStockReservationUseCase, never()).execute();
    }

    @Test
    @DisplayName("runs a stale tick when this instance has not swept yet (restart, clock skew)")
    void shouldRunStaleTickWithoutRecentSweep() {
        consumer.consume("tick", sentAgo(Duration.ofMinutes(5)));

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs a stale tick when the last sweep is older than maxTickAge")
    void shouldRunStaleTickWhenLastSweepIsOld() {
        sweepRecently();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(3)));

        consumer.consume("tick", String.valueOf(NOW.minus(Duration.ofMinutes(2)).toEpochMilli()));

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs a tick exactly at maxTickAge")
    void shouldRunTickAtThreshold() {
        sweepRecently();

        consumer.consume("tick", sentAgo(Duration.ofMinutes(2)));

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs when the SentTimestamp header is missing")
    void shouldRunWhenHeaderMissing() {
        sweepRecently();

        consumer.consume("tick", null);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs when the SentTimestamp header is unparseable")
    void shouldRunWhenHeaderUnparseable() {
        sweepRecently();

        consumer.consume("tick", "garbage");

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs when the SentTimestamp is in the future")
    void shouldRunWhenTimestampInFuture() {
        sweepRecently();

        consumer.consume("tick", String.valueOf(NOW.plusSeconds(30).toEpochMilli()));

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("after a streak of stale ticks, a fresh tick runs the sweep once")
    void shouldRunOnceAfterStaleStreak() {
        sweepRecently();

        consumer.consume("tick", sentAgo(Duration.ofMinutes(10)));
        consumer.consume("tick", sentAgo(Duration.ofMinutes(9)));
        consumer.consume("tick", sentAgo(Duration.ofSeconds(3)));

        verify(expireStockReservationUseCase, times(1)).execute();
    }

    @Test
    @DisplayName("propagates a sweep failure so the tick is not acknowledged; its stale redelivery is then dropped")
    void shouldPropagateSweepFailure() {
        doThrow(new IllegalStateException("db down")).when(expireStockReservationUseCase).execute();

        assertThrows(IllegalStateException.class, () -> consumer.consume("tick", sentAgo(Duration.ofSeconds(5))));

        clearInvocations(expireStockReservationUseCase);
        consumer.consume("tick", sentAgo(Duration.ofMinutes(3)));
        verify(expireStockReservationUseCase, never()).execute();
    }

    @Test
    @DisplayName("reads the SQS SentTimestamp system attribute header")
    void shouldReadSentTimestampHeader() throws NoSuchMethodException {
        Header header = (Header) SqsReservationExpireConsumer.class
                .getMethod("consume", String.class, String.class)
                .getParameterAnnotations()[1][0];

        assertEquals("Sqs_Msa_SentTimestamp", header.name());
        assertFalse(header.required());
    }

    @Test
    @DisplayName("listener takes one tick at a time with a visibility timeout above the sweep time budget")
    void shouldConfigureListenerForOneTickInFlight() throws NoSuchMethodException {
        SqsListener listener = SqsReservationExpireConsumer.class
                .getMethod("consume", String.class, String.class)
                .getAnnotation(SqsListener.class);

        assertEquals("1", listener.maxConcurrentMessages());
        assertEquals("1", listener.maxMessagesPerPoll());
        assertEquals("90", listener.messageVisibilitySeconds());
    }
}
