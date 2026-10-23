package com.dotran.oms.inventory.application.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of the reservation expiry sweep, triggered by the EventBridge tick (every minute) through SQS.
 */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "app.reservation-expiry")
@Validated
@Getter
@Setter
public class ReservationExpiryProperties {

    /** EventBridge schedule of the tick ({@code rate(1 minute)} in the development repo Terraform). */
    public static final Duration TICK_INTERVAL = Duration.ofMinutes(1);

    /** Visibility timeout the listener sets on a received tick; a sweep must finish well within it. */
    public static final String LISTENER_VISIBILITY_SECONDS = "90";

    /** Reservations loaded and expired per page. */
    @Positive
    private int batchSize = 500;

    /** A sweep stops starting new pages after this long; the rest is left for the next tick. */
    @NotNull
    private Duration timeBudget = Duration.ofSeconds(40);

    /** A tick older than this is dropped when this instance already swept recently. */
    @NotNull
    private Duration maxTickAge = Duration.ofMinutes(2);

    /** Room left in the visibility timeout for the last page, which may start just before the budget ends. */
    static final Duration LAST_PAGE_MARGIN = Duration.ofSeconds(30);

    @AssertTrue(message = "app.reservation-expiry.time-budget must be positive and at most the listener visibility timeout minus 30 s")
    public boolean isTimeBudgetWithinVisibility() {
        Duration visibility = Duration.ofSeconds(Long.parseLong(LISTENER_VISIBILITY_SECONDS));
        return timeBudget == null
                || (!timeBudget.isNegative() && !timeBudget.isZero()
                && timeBudget.compareTo(visibility.minus(LAST_PAGE_MARGIN)) <= 0);
    }

    /**
     * A tick can wait behind a running sweep for up to the time budget; with a shorter max age such ticks would be
     * dropped and roughly every other tick skipped.
     */
    @AssertTrue(message = "app.reservation-expiry.max-tick-age must be longer than the tick interval plus the time budget")
    public boolean isMaxTickAgeLongerThanTickIntervalPlusBudget() {
        return maxTickAge == null || timeBudget == null
                || maxTickAge.compareTo(TICK_INTERVAL.plus(timeBudget)) > 0;
    }
}
