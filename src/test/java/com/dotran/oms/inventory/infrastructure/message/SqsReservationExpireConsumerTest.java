package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.config.ReservationExpiryProperties;
import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.Header;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import software.amazon.awssdk.services.sqs.model.Message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SqsReservationExpireConsumer")
class SqsReservationExpireConsumerTest {

    private static final String SQS_ID = "0d9b3c4e-1f2a-4b5c-8d6e-7f8091a2b3c4";
    private static final Message MESSAGE_ID = Message.builder().messageId(SQS_ID).body("tick").build();

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
        consumer.consume("tick", sentAgo(Duration.ofSeconds(5)), MESSAGE_ID);
        clearInvocations(expireStockReservationUseCase);
    }

    @Test
    @DisplayName("runs the sweep for a fresh tick")
    void shouldRunForFreshTick() {
        consumer.consume("tick", sentAgo(Duration.ofSeconds(10)), MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("drops a stale tick when this instance swept recently, returning normally so it is acknowledged")
    void shouldDropStaleTickAfterRecentSweep() {
        sweepRecently();

        assertDoesNotThrow(() -> consumer.consume("tick", sentAgo(Duration.ofMinutes(5)), MESSAGE_ID));

        verify(expireStockReservationUseCase, never()).execute();
    }

    @Test
    @DisplayName("runs a stale tick when this instance has not swept yet (restart, clock skew)")
    void shouldRunStaleTickWithoutRecentSweep() {
        consumer.consume("tick", sentAgo(Duration.ofMinutes(5)), MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs a stale tick when the last sweep is older than maxTickAge")
    void shouldRunStaleTickWhenLastSweepIsOld() {
        sweepRecently();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(3)));

        consumer.consume("tick", String.valueOf(NOW.minus(Duration.ofMinutes(2)).toEpochMilli()), MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs a tick exactly at maxTickAge")
    void shouldRunTickAtThreshold() {
        sweepRecently();

        consumer.consume("tick", sentAgo(Duration.ofMinutes(2)), MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs when the SentTimestamp header is missing")
    void shouldRunWhenHeaderMissing() {
        sweepRecently();

        consumer.consume("tick", null, MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs when the SentTimestamp header is unparseable")
    void shouldRunWhenHeaderUnparseable() {
        sweepRecently();

        consumer.consume("tick", "garbage", MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("runs when the SentTimestamp is in the future")
    void shouldRunWhenTimestampInFuture() {
        sweepRecently();

        consumer.consume("tick", String.valueOf(NOW.plusSeconds(30).toEpochMilli()), MESSAGE_ID);

        verify(expireStockReservationUseCase).execute();
    }

    @Test
    @DisplayName("after a streak of stale ticks, a fresh tick runs the sweep once")
    void shouldRunOnceAfterStaleStreak() {
        sweepRecently();

        consumer.consume("tick", sentAgo(Duration.ofMinutes(10)), MESSAGE_ID);
        consumer.consume("tick", sentAgo(Duration.ofMinutes(9)), MESSAGE_ID);
        consumer.consume("tick", sentAgo(Duration.ofSeconds(3)), MESSAGE_ID);

        verify(expireStockReservationUseCase, times(1)).execute();
    }

    @Test
    @DisplayName("propagates a sweep failure so the tick is not acknowledged; its stale redelivery is then dropped")
    void shouldPropagateSweepFailure() {
        doThrow(new IllegalStateException("db down")).when(expireStockReservationUseCase).execute();

        assertThrows(IllegalStateException.class, () -> consumer.consume("tick", sentAgo(Duration.ofSeconds(5)), MESSAGE_ID));

        clearInvocations(expireStockReservationUseCase);
        consumer.consume("tick", sentAgo(Duration.ofMinutes(3)), MESSAGE_ID);
        verify(expireStockReservationUseCase, never()).execute();
    }

    @Test
    @DisplayName("reads the SQS SentTimestamp system attribute header")
    void shouldReadSentTimestampHeader() throws NoSuchMethodException {
        Header header = (Header) SqsReservationExpireConsumer.class
                .getMethod("consume", String.class, String.class, Message.class)
                .getParameterAnnotations()[1][0];

        assertEquals("Sqs_Msa_SentTimestamp", header.name());
        assertFalse(header.required());
    }

    @Test
    @DisplayName("listener takes one tick at a time with a visibility timeout above the sweep time budget")
    void shouldConfigureListenerForOneTickInFlight() throws NoSuchMethodException {
        SqsListener listener = SqsReservationExpireConsumer.class
                .getMethod("consume", String.class, String.class, Message.class)
                .getAnnotation(SqsListener.class);

        assertEquals("1", listener.maxConcurrentMessages());
        assertEquals("1", listener.maxMessagesPerPoll());
        assertEquals("90", listener.messageVisibilitySeconds());
    }

    @Test
    @DisplayName("MDC carries the SQS message id during the sweep and is empty afterwards")
    void shouldSetMdcDuringSweep() {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        doAnswer(inv -> {
            seen.put("requestId", MDC.get("requestId"));
            seen.put("sqsMessageId", MDC.get("sqsMessageId"));
            return null;
        }).when(expireStockReservationUseCase).execute();

        consumer.consume("tick", sentAgo(Duration.ofSeconds(5)), MESSAGE_ID);

        assertEquals(SQS_ID, seen.get("requestId"));
        assertEquals(SQS_ID, seen.get("sqsMessageId"));
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }

    @Test
    @DisplayName("MDC is cleared when the sweep fails and when there is no message id")
    void shouldClearMdcOnFailure() {
        doThrow(new IllegalStateException("db down")).when(expireStockReservationUseCase).execute();

        assertThrows(IllegalStateException.class, () -> consumer.consume("tick", sentAgo(Duration.ofSeconds(5)), null));

        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }
}
