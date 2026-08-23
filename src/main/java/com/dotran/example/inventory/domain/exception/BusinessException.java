package com.dotran.example.inventory.domain.exception;

public class BusinessException extends RuntimeException {

    private static final String ERROR_MESSAGE = "Business Error";

    public BusinessException() {
        super(ERROR_MESSAGE);
    }

    public BusinessException(String errorMessage) {
        super(errorMessage);
    }
}
