package com.dotran.oms.inventory.domain.enums;

/** Why one line of a reserve was rejected (BR-INV-11). Never carries a stock count (BR-CAT-04). */
public enum LineRejectionReason {
    INSUFFICIENT_STOCK,
    /** The inventory is INACTIVE (BR-INV-08). */
    NOT_ORDERABLE,
    /** No inventory record for this SKU in the store, or it belongs to another product (BR-CAT-04). */
    UNKNOWN_SKU
}
