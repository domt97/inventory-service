package com.dotran.oms.inventory.domain.exception;

public class InvalidQuantityException extends RuntimeException {

    public InvalidQuantityException() {
        super("Invalid quantity");
    }

    public InvalidQuantityException(String message) {
        super(message);
    }
}
