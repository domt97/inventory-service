package com.dotran.oms.inventory.application.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of the stock commands (reserve, confirm, release; ADR 0006).
 * {@code transaction-timeout-seconds} is also read by {@code @Transactional(timeoutString = ...)} on the three use
 * cases through {@link #TRANSACTION_TIMEOUT}, so the value checked here is the value used.
 */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "app.reservation")
@Validated
@Getter
@Setter
public class ReservationProperties {

    /** Placeholder used by the use cases' {@code @Transactional(timeoutString = ...)}; same key as the property below. */
    public static final String TRANSACTION_TIMEOUT = "${app.reservation.transaction-timeout-seconds:10}";

    /** Hold time of a reservation (BR-INV-02, 15 minutes by default). */
    @NotNull
    private Duration ttl = Duration.ofMinutes(15);

    /** Most lines one reserve may hold (BR-INV-11). */
    @Min(1)
    @Max(100) // also the reply schemas' lines.maxItems (StockReserved, StockReservationExpired …): raise both together
    private int maxLines = 100;

    /** Clock difference tolerated by the stale-command check (BR-INV-11). */
    @NotNull
    private Duration clockSkewTolerance = Duration.ofSeconds(30);

    /** {@code lock_timeout} of a command transaction: how long one statement may wait for a row lock. */
    @NotNull
    private Duration lockTimeout = Duration.ofSeconds(3);

    /** {@code statement_timeout} of a command transaction: longest single statement. */
    @NotNull
    private Duration statementTimeout = Duration.ofSeconds(5);

    /** Bound of a whole command transaction, in seconds. */
    @Min(1)
    private int transactionTimeoutSeconds = 10;

    @AssertTrue(message = "app.reservation.ttl must be positive and at most 24 hours")
    public boolean isTtlValid() {
        return ttl == null || (!ttl.isNegative() && !ttl.isZero() && ttl.compareTo(Duration.ofHours(24)) <= 0);
    }

    @AssertTrue(message = "app.reservation.clock-skew-tolerance must be zero or positive and shorter than the ttl")
    public boolean isClockSkewToleranceValid() {
        return clockSkewTolerance == null || ttl == null
                || (!clockSkewTolerance.isNegative() && clockSkewTolerance.compareTo(ttl) < 0);
    }

    @AssertTrue(message = "app.reservation: 0 < lock-timeout < statement-timeout < transaction-timeout-seconds")
    public boolean isTimeoutsOrdered() {
        return lockTimeout == null || statementTimeout == null
                || (!lockTimeout.isNegative() && !lockTimeout.isZero()
                && lockTimeout.compareTo(statementTimeout) < 0
                && statementTimeout.compareTo(Duration.ofSeconds(transactionTimeoutSeconds)) < 0);
    }
}
