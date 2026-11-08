package com.dotran.oms.inventory.domain.enums;

/** Status of the stock header of one order ({@code stock_reservation_orders}, ADR 0006). */
public enum OrderReservationStatus {
    /** Holds placed; can be confirmed or released. */
    RESERVED,
    /** The reserve was rejected; nothing is held. */
    REJECTED,
    /** Stock deducted (order PLACED). */
    CONFIRMED,
    /** Closed by a release, or a tombstone left by a release or confirm that came before any reserve. */
    CANCELLED
}
