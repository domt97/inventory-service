package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;

import java.util.Optional;

/** Order headers of the stock commands ({@code stock_reservation_orders}, ADR 0006). All methods need a transaction. */
public interface StockReservationOrderRepository {

    /**
     * {@code INSERT ... ON CONFLICT (order_id) DO NOTHING}. A concurrent insert of the same order waits for the other
     * transaction (bounded by {@code lock_timeout}).
     *
     * @return true if this call created the header, false if it already existed
     */
    boolean insertIfAbsent(StockReservationOrder header);

    /**
     * Locks the header ({@code SELECT ... FOR UPDATE}) scoped by order, merchant and store.
     *
     * @return empty when the order does not exist or belongs to another merchant or store
     */
    Optional<StockReservationOrder> lockForUpdate(OrderId orderId, MerchantId merchantId, StoreId storeId);

    /** Writes status, restock and rejection of a locked header; 0 if its status is no longer {@code expectedStatus}. */
    int update(StockReservationOrder header, OrderReservationStatus expectedStatus);
}
