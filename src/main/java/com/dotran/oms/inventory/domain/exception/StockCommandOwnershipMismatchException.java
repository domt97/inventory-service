package com.dotran.oms.inventory.domain.exception;

/**
 * The order of a stock command already belongs to another merchant or store: nothing is written, and the command goes
 * to its DLT (ADR 0006).
 */
public class StockCommandOwnershipMismatchException extends RuntimeException {

    public StockCommandOwnershipMismatchException(String message) {
        super(message);
    }
}
