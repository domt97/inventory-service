package com.dotran.example.inventory.domain.exception;

public class InvalidReservationException extends RuntimeException {

    public InvalidReservationException() {
        super("Invalid Reservation");
    }

    public InvalidReservationException(String errorMessage) {
        super(errorMessage);
    }
}
