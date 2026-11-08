package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;

import java.util.List;

/**
 * Why a reserve was rejected, stored on the order header so a duplicate reserve gets the same answer (BR-INV-07).
 * Either the whole order ({@code orderReason}, no lines) or some lines (reason per rejected line).
 */
public record ReservationRejection(OrderRejectionReason orderReason, List<Line> lines) {

    public ReservationRejection {
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (orderReason == null && lines.isEmpty()) {
            throw new IllegalArgumentException("A rejection needs an order reason or at least one rejected line");
        }
        if (orderReason != null && !lines.isEmpty()) {
            throw new IllegalArgumentException("A whole-order rejection has no lines");
        }
    }

    public static ReservationRejection ofOrder(OrderRejectionReason reason) {
        return new ReservationRejection(reason, List.of());
    }

    public static ReservationRejection ofLines(List<Line> lines) {
        return new ReservationRejection(null, lines);
    }

    /** One rejected line. */
    public record Line(OrderItemId orderItemId, SKU sku, LineRejectionReason reason) {
    }
}
