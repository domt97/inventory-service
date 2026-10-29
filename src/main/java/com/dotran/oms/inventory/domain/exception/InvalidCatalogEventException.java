package com.dotran.oms.inventory.domain.exception;

/** A catalog event (ProductCreated / ProductUpdated) that breaks its contract; never retried, sent to the dead-letter topic. */
public class InvalidCatalogEventException extends RuntimeException {

    public InvalidCatalogEventException(String message) {
        super(message);
    }
}
