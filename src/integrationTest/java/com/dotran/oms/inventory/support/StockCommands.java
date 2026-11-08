package com.dotran.oms.inventory.support;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds stock commands for one merchant, store and product. */
public record StockCommands(UUID merchant, UUID store, UUID product) {

    public ReserveStockCmd.Line line(UUID orderItem, String sku, long quantity) {
        return ReserveStockCmd.Line.builder().orderItemId(OrderItemId.of(orderItem)).productId(ProductId.of(product))
                .sku(SKU.of(sku)).quantity(quantity).build();
    }

    public ReserveStockCmd reserve(UUID order, ReserveStockCmd.Line... lines) {
        return ReserveStockCmd.builder().eventId(EventId.newEventId()).occurredAt(Instant.now())
                .merchantId(MerchantId.of(merchant)).storeId(StoreId.of(store)).orderId(OrderId.of(order))
                .lines(List.of(lines)).build();
    }

    public ConfirmStockReservationCmd confirm(UUID order) {
        return ConfirmStockReservationCmd.builder().eventId(EventId.newEventId()).occurredAt(Instant.now())
                .merchantId(MerchantId.of(merchant)).storeId(StoreId.of(store)).orderId(OrderId.of(order)).build();
    }

    public ReleaseStockReservationCmd release(UUID order, boolean restock) {
        return ReleaseStockReservationCmd.builder().eventId(EventId.newEventId()).occurredAt(Instant.now())
                .merchantId(MerchantId.of(merchant)).storeId(StoreId.of(store)).orderId(OrderId.of(order))
                .restock(restock).reason("TEST").build();
    }
}
