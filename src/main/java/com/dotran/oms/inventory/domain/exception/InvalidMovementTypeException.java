package com.dotran.oms.inventory.domain.exception;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.inventory.domain.enums.StockMovementType;

/** A movement type that cannot be used for a manual adjustment (the order hold types, BR-INV-05): 400. */
public class InvalidMovementTypeException extends ValidationException {

    public InvalidMovementTypeException(StockMovementType type) {
        super("Stock movement type " + type + " cannot be used for a manual adjustment");
    }
}
