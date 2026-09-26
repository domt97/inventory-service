package com.dotran.oms.inventory.domain.exception;

public class InvalidReservationStateException extends RuntimeException {

    public InvalidReservationStateException() {
        super("Invalid reservation state");
    }

    public InvalidReservationStateException(String message) {
        super(message);
    }
}
