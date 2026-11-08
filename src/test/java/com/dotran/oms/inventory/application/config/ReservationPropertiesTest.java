package com.dotran.oms.inventory.application.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReservationProperties")
class ReservationPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private Set<ConstraintViolation<ReservationProperties>> validate(ReservationProperties properties) {
        return validator.validate(properties);
    }

    @Test
    @DisplayName("defaults are valid: 15 min ttl, 100 lines, 30 s skew, 3 s / 5 s / 10 s timeouts")
    void defaults() {
        ReservationProperties properties = new ReservationProperties();

        assertTrue(validate(properties).isEmpty());
        assertEquals(Duration.ofMinutes(15), properties.getTtl());
        assertEquals(100, properties.getMaxLines());
        assertEquals(Duration.ofSeconds(30), properties.getClockSkewTolerance());
        assertEquals(Duration.ofSeconds(3), properties.getLockTimeout());
        assertEquals(Duration.ofSeconds(5), properties.getStatementTimeout());
        assertEquals(10, properties.getTransactionTimeoutSeconds());
    }

    @Test
    @DisplayName("the @Transactional placeholder reads the same key with the same default")
    void transactionTimeoutPlaceholder() {
        assertEquals("${app.reservation.transaction-timeout-seconds:10}", ReservationProperties.TRANSACTION_TIMEOUT);
    }

    @Test
    @DisplayName("ttl must be positive and at most 24 hours")
    void ttlBounds() {
        ReservationProperties properties = new ReservationProperties();
        properties.setTtl(Duration.ZERO);
        assertFalse(validate(properties).isEmpty());

        properties.setTtl(Duration.ofHours(25));
        assertFalse(validate(properties).isEmpty());

        properties.setTtl(Duration.ofHours(24));
        assertTrue(validate(properties).isEmpty());
    }

    @Test
    @DisplayName("max-lines must be 1..100 (BR-INV-11)")
    void maxLinesBounds() {
        ReservationProperties properties = new ReservationProperties();
        properties.setMaxLines(0);
        assertFalse(validate(properties).isEmpty());

        properties.setMaxLines(101);
        assertFalse(validate(properties).isEmpty());
    }

    @Test
    @DisplayName("clock-skew-tolerance must be shorter than the ttl and not negative")
    void skewBounds() {
        ReservationProperties properties = new ReservationProperties();
        properties.setClockSkewTolerance(Duration.ofMinutes(15));
        assertFalse(validate(properties).isEmpty());

        properties.setClockSkewTolerance(Duration.ofSeconds(-1));
        assertFalse(validate(properties).isEmpty());

        properties.setClockSkewTolerance(Duration.ZERO);
        assertTrue(validate(properties).isEmpty());
    }

    @Test
    @DisplayName("timeouts must be ordered: 0 < lock < statement < transaction")
    void timeoutOrder() {
        ReservationProperties properties = new ReservationProperties();
        properties.setLockTimeout(Duration.ofSeconds(5));
        assertFalse(validate(properties).isEmpty(), "lock == statement");

        properties = new ReservationProperties();
        properties.setStatementTimeout(Duration.ofSeconds(10));
        assertFalse(validate(properties).isEmpty(), "statement == transaction");

        properties = new ReservationProperties();
        properties.setLockTimeout(Duration.ZERO);
        assertFalse(validate(properties).isEmpty(), "lock zero");

        properties = new ReservationProperties();
        properties.setTransactionTimeoutSeconds(0);
        assertFalse(validate(properties).isEmpty(), "transaction zero");
    }
}
