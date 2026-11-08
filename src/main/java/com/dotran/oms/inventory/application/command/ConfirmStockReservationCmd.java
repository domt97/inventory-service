package com.dotran.oms.inventory.application.command;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** ConfirmStockReservation command (ADR 0006): the order is PLACED, deduct its held stock (BR-INV-04). */
@Value
@Builder
public class ConfirmStockReservationCmd {

    EventId eventId;
    Instant occurredAt;
    MerchantId merchantId;
    StoreId storeId;
    OrderId orderId;
}
