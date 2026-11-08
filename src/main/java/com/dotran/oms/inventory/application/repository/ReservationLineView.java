package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;

import java.time.Instant;

/**
 * One reservation of an order with its SKU and product, read with native SQL (never through the persistence context,
 * whose entities keep their old status after the native guarded transitions).
 */
public record ReservationLineView(StockReservationId id,
                                  OrderItemId orderItemId,
                                  InventoryId inventoryId,
                                  ProductId productId,
                                  SKU sku,
                                  long quantity,
                                  ReservationStatus status,
                                  Instant expiresAt) {
}
