package com.dotran.oms.inventory.application.command;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * ReleaseStockReservation command (ADR 0006): close the order's stock lifecycle. RESERVED lines are released;
 * CONFIRMED lines are returned to stock when {@code restock} (BR-ORD-09), otherwise they stay sold.
 */
@Value
@Builder
public class ReleaseStockReservationCmd {

    EventId eventId;
    Instant occurredAt;
    MerchantId merchantId;
    StoreId storeId;
    OrderId orderId;
    Boolean restock;
    String reason;
}
