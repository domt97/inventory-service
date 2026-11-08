package com.dotran.oms.inventory.application.dto.reservation;

import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.model.ReservationRejection;

import java.time.Instant;
import java.util.List;

/** Outcome of a reserve; a later PR publishes it as StockReserved / StockReservationRejected (ADR 0006). */
public sealed interface ReserveStockResult {

    /** Every line is held until {@code expiresAt}. For a duplicate reserve, the outcome of the original reserve. */
    record Reserved(Instant expiresAt, List<Line> lines) implements ReserveStockResult {
        public Reserved {
            lines = List.copyOf(lines);
        }
    }

    /** Nothing is held: the whole order ({@code orderReason}) or some lines were refused. */
    record Rejected(OrderRejectionReason orderReason, List<ReservationRejection.Line> lines) implements ReserveStockResult {
        public Rejected {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public static Rejected of(ReservationRejection rejection) {
            return new Rejected(rejection.orderReason(), rejection.lines());
        }
    }

    record Line(OrderItemId orderItemId, SKU sku, long quantity, Instant expiresAt) {
    }
}
