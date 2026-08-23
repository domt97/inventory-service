package com.dotran.example.inventory.common.exception;

import com.dotran.example.inventory.common.constants.Constants;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException() {
        super(Constants.ERROR_MSG_NOT_FOUND);
    }

    public NotFoundException(String resourceName, String id) {
        super(resourceName + " with ID " + id + " not found");
    }
}
