package com.dotran.example.inventory.domain.exception;

public class InvalidQuantityException extends RuntimeException {

    public InvalidQuantityException() {
        super("Invalid quantity");
    }

    public InvalidQuantityException(String message) {
        super(message);
    }
}
