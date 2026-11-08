package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;

/**
 * The stock header of one order ({@code stock_reservation_orders}, ADR 0006): every stock command of the order locks it
 * first, so commands are applied once (BR-INV-07) and in any order. Lines live in {@link StockReservation}.
 *
 * @param restock   fixed by the first release (BR-ORD-09); null before it
 * @param rejection set when {@code status} is REJECTED
 */
public record StockReservationOrder(OrderId orderId,
                                    MerchantId merchantId,
                                    StoreId storeId,
                                    OrderReservationStatus status,
                                    Boolean restock,
                                    ReservationRejection rejection) {

    public boolean isClosed() {
        return status == OrderReservationStatus.REJECTED || status == OrderReservationStatus.CANCELLED;
    }

    /** True when no release has fixed {@code restock} yet: this release is the first one. */
    public boolean isFirstRelease() {
        return restock == null;
    }
}
