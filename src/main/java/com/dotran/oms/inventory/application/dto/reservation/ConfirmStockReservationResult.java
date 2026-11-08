package com.dotran.oms.inventory.application.dto.reservation;

import java.util.List;

/** Outcome of a confirm; a later PR publishes it as StockReservationConfirmed / ...ConfirmationFailed (ADR 0006). */
public sealed interface ConfirmStockReservationResult {

    /** All lines are CONFIRMED (stock deducted). */
    record Confirmed(List<ReservationLineStatus> lines) implements ConfirmStockReservationResult {
        public Confirmed {
            lines = List.copyOf(lines);
        }
    }

    /** Nothing was confirmed. */
    record ConfirmationFailed(Reason reason, List<ReservationLineStatus> lines) implements ConfirmStockReservationResult {
        public ConfirmationFailed {
            lines = List.copyOf(lines);
        }
    }

    enum Reason {
        /** No reservation for this order; inventory closed the order (tombstone). */
        UNKNOWN_ORDER,
        /** Released or rejected before. */
        ORDER_CLOSED,
        /** At least one line expired or was released. */
        NOT_RESERVED
    }
}
