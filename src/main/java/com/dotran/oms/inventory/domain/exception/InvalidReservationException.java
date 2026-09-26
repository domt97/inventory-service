package com.dotran.oms.inventory.domain.exception;

public class InvalidReservationException extends RuntimeException {

    public InvalidReservationException() {
        super("Invalid Reservation");
    }

    public InvalidReservationException(String errorMessage) {
        super(errorMessage);
    }
}
