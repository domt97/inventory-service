package com.dotran.example.inventory.domain.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException() {
        super("Insufficient stock available for the requested operation.");
    }

    public InsufficientStockException(String message) {
        super(message);
    }
}
