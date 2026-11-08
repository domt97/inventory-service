package com.dotran.oms.inventory.domain.exception;

/** The inventory row is not orderable (status INACTIVE, BR-INV-08): it cannot be reserved. */
public class InventoryNotOrderableException extends RuntimeException {

    public InventoryNotOrderableException() {
        super("Inventory is not orderable");
    }
}
