package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;

/**
 * The stock header of one order ({@code stock_reservation_orders}, ADR 0006): every stock command of the order locks it
 * first, so commands are applied once (BR-INV-07) and in any order. Lines live in {@link StockReservation}.
 *
 * <p>The constructor enforces the same rules as the V4 CHECKs, so an invalid header fails in Java (and in unit tests)
 * instead of at the database. This matters for {@code insertIfAbsent}: Postgres checks the CHECKs on the proposed row
 * before {@code ON CONFLICT DO NOTHING}, so an invalid row would make a duplicate command fail instead of being absorbed.
 *
 * @param restock   fixed by the first release (BR-ORD-09); null before it; only on a closed header
 * @param rejection required when {@code status} is REJECTED
 */
public record StockReservationOrder(OrderId orderId,
                                    MerchantId merchantId,
                                    StoreId storeId,
                                    OrderReservationStatus status,
                                    Boolean restock,
                                    ReservationRejection rejection) {

    public StockReservationOrder {
        if (orderId == null || merchantId == null || storeId == null || status == null) {
            throw new IllegalArgumentException("An order header needs orderId, merchantId, storeId and status");
        }
        if (status == OrderReservationStatus.REJECTED && rejection == null) {
            throw new IllegalArgumentException("A REJECTED order header needs its rejection");
        }
        if (restock != null && !(status == OrderReservationStatus.REJECTED
                || status == OrderReservationStatus.CANCELLED)) {
            throw new IllegalArgumentException("restock is only set on a REJECTED or CANCELLED order header, not " + status);
        }
    }

    public boolean isClosed() {
        return status == OrderReservationStatus.REJECTED || status == OrderReservationStatus.CANCELLED;
    }

    /** True when no release has fixed {@code restock} yet: this release is the first one. */
    public boolean isFirstRelease() {
        return restock == null;
    }
}
