package com.dotran.oms.inventory.domain.exception;

/**
 * A stock command that breaks its contract (missing ids, bad lines, the same order with different lines): nothing is
 * written. The command consumer sends such a command to its DLT (ADR 0006).
 */
public class InvalidStockCommandException extends RuntimeException {

    public InvalidStockCommandException(String message) {
        super(message);
    }
}
