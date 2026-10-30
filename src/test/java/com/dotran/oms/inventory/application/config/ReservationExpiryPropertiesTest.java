package com.dotran.oms.inventory.application.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReservationExpiryProperties")
class ReservationExpiryPropertiesTest {

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

    private Set<ConstraintViolation<ReservationExpiryProperties>> validate(ReservationExpiryProperties properties) {
        return validator.validate(properties);
    }

    @Test
    @DisplayName("defaults are valid")
    void defaultsAreValid() {
        assertTrue(validate(new ReservationExpiryProperties()).isEmpty());
    }

    @Test
    @DisplayName("rejects a non-positive batch size")
    void rejectsNonPositiveBatchSize() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setBatchSize(0);

        assertEquals(1, validate(properties).size());
    }

    @Test
    @DisplayName("rejects a time budget not shorter than the listener visibility timeout")
    void rejectsTimeBudgetAtVisibility() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setTimeBudget(Duration.ofSeconds(90));
        properties.setMaxTickAge(Duration.ofMinutes(3));

        assertEquals(1, validate(properties).size());
    }

    @Test
    @DisplayName("rejects a zero time budget")
    void rejectsZeroTimeBudget() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setTimeBudget(Duration.ZERO);

        assertEquals(1, validate(properties).size());
    }

    @Test
    @DisplayName("accepts a time budget of visibility minus 30 s")
    void acceptsTimeBudgetAtMargin() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setTimeBudget(Duration.ofSeconds(60));
        properties.setMaxTickAge(Duration.ofMinutes(3));

        assertTrue(validate(properties).isEmpty());
    }

    @Test
    @DisplayName("rejects a time budget that leaves less than 30 s of visibility for the last page")
    void rejectsTimeBudgetBeyondMargin() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setTimeBudget(Duration.ofSeconds(61));
        properties.setMaxTickAge(Duration.ofMinutes(3));

        assertEquals(1, validate(properties).size());
    }

    @Test
    @DisplayName("rejects a max tick age not longer than the tick interval plus the time budget")
    void rejectsMaxTickAgeWithinIntervalPlusBudget() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setMaxTickAge(Duration.ofSeconds(100)); // 60 s interval + 40 s default budget

        assertEquals(1, validate(properties).size());
    }

    @Test
    @DisplayName("is @Validated so Spring checks it at startup")
    void isValidatedAtStartup() {
        assertTrue(ReservationExpiryProperties.class.isAnnotationPresent(Validated.class));
    }
}
