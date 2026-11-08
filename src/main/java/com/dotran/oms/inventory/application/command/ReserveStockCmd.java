package com.dotran.oms.inventory.application.command;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/** ReserveStock command (ADR 0006): hold every line of one order, all or nothing (BR-INV-11). */
@Value
@Builder
public class ReserveStockCmd {

    EventId eventId;
    Instant occurredAt;
    MerchantId merchantId;
    StoreId storeId;
    OrderId orderId;
    List<Line> lines;

    @Value
    @Builder
    public static class Line {

        OrderItemId orderItemId;
        ProductId productId;
        SKU sku;
        long quantity;
    }
}
