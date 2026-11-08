package com.dotran.oms.inventory.domain.enums;

public enum ReservationStatus {

    RESERVED,
    CONFIRMED,
    RELEASED,
    EXPIRED,
    /** Confirmed goods put back into stock by a release with restock (BR-INV-03, BR-ORD-09). */
    RETURNED,
    /** Unused: a cancelled order releases its holds (BR-INV-03). Kept for compatibility of stored values. */
    CANCELLED
}
