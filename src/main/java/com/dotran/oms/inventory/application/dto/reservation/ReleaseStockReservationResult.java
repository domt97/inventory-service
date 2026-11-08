package com.dotran.oms.inventory.application.dto.reservation;

import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;

import java.util.List;

/**
 * Outcome of a release; a later PR publishes it as StockReservationReleased (ADR 0006).
 *
 * @param restock the value fixed by the first release of the order
 */
public record ReleaseStockReservationResult(boolean restock, List<Line> lines) {

    public ReleaseStockReservationResult {
        lines = List.copyOf(lines);
    }

    /** @param restocked true only for a line returned to stock by this order (RETURNED) */
    public record Line(OrderItemId orderItemId, ReservationStatus status, boolean restocked) {
    }
}
