package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.config.ReservationExpiryProperties;
import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The tick is only a signal: its body is ignored and every sweep re-reads the database. One tick is in flight per
 * instance; a stale tick (backlog after downtime, redelivery) is dropped when this instance already swept recently.
 */
@Component
@Slf4j
public class SqsReservationExpireConsumer {

    private static final long STALE_WARN_EVERY = 60;

    private final ExpireStockReservationUseCase expireStockReservationUseCase;
    private final Clock clock;
    private final ReservationExpiryProperties reservationExpiryProperties;

    private final AtomicReference<Instant> lastSweepStartedAt = new AtomicReference<>();
    private final AtomicLong consecutiveStaleDrops = new AtomicLong();

    public SqsReservationExpireConsumer(ExpireStockReservationUseCase expireStockReservationUseCase,
                                        Clock clock,
                                        ReservationExpiryProperties reservationExpiryProperties) {
        this.expireStockReservationUseCase = expireStockReservationUseCase;
        this.clock = clock;
        this.reservationExpiryProperties = reservationExpiryProperties;
    }

    @SqsListener(value = "${aws.sqs.queues.reservation-expire}",
            maxConcurrentMessages = "1",
            maxMessagesPerPoll = "1",
            messageVisibilitySeconds = ReservationExpiryProperties.LISTENER_VISIBILITY_SECONDS)
    public void consume(String message,
                        @Header(name = SqsHeaders.MessageSystemAttributes.SQS_SENT_TIMESTAMP, required = false)
                        String sentTimestamp) {
        Instant now = clock.instant();
        Duration age = tickAge(sentTimestamp, now);

        if (shouldDrop(age, now)) {
            long dropped = consecutiveStaleDrops.incrementAndGet();
            if (dropped == 1 || dropped % STALE_WARN_EVERY == 0) {
                log.warn("Dropping stale reservation expire tick (age {}, max {}); {} dropped in a row. "
                                + "A long streak means a backlog or clock skew between SQS and this instance",
                        age, reservationExpiryProperties.getMaxTickAge(), dropped);
            } else {
                log.debug("Dropping stale reservation expire tick (age {})", age);
            }
            return;
        }

        long dropped = consecutiveStaleDrops.getAndSet(0);
        if (dropped > 0) {
            log.info("Dropped {} stale reservation expire ticks before this one", dropped);
        }

        log.info("Received reservation expire event (age {})", age);
        lastSweepStartedAt.set(now);

        expireStockReservationUseCase.execute();

        log.info("Finished processing reservation expire event");
    }

    /**
     * Fails open: an unknown age (missing or unparseable header, timestamp in the future) never drops a tick, and
     * neither does a stale one when this instance has not swept within {@code maxTickAge} (e.g. after a restart or
     * when the clocks of SQS and this instance disagree).
     */
    private boolean shouldDrop(Duration age, Instant now) {
        Duration maxTickAge = reservationExpiryProperties.getMaxTickAge();
        if (age == null || age.compareTo(maxTickAge) <= 0) {
            return false;
        }
        Instant lastSweep = lastSweepStartedAt.get();
        return lastSweep != null && lastSweep.isAfter(now.minus(maxTickAge));
    }

    private static Duration tickAge(String sentTimestamp, Instant now) {
        if (sentTimestamp == null) {
            return null;
        }
        try {
            Duration age = Duration.between(Instant.ofEpochMilli(Long.parseLong(sentTimestamp.trim())), now);
            return age.isNegative() ? null : age;
        } catch (NumberFormatException e) {
            log.warn("Unparseable SentTimestamp on reservation expire tick: {}", sentTimestamp);
            return null;
        }
    }
}
