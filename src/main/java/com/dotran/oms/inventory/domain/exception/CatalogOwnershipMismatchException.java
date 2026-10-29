package com.dotran.oms.inventory.domain.exception;

/** A catalog event whose merchant or store differs from what inventory already holds for the product; nothing is written. */
public class CatalogOwnershipMismatchException extends RuntimeException {

    public CatalogOwnershipMismatchException(String message) {
        super(message);
    }
}
