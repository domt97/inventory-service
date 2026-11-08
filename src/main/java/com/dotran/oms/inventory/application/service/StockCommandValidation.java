package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Contract checks of the stock commands, run before any write: a violation means nothing is written (DLT, ADR 0006). */
final class StockCommandValidation {

    static final int MAX_SKU_LENGTH = 100;
    static final int MAX_REASON_LENGTH = 50;

    private StockCommandValidation() {
    }

    static void requireCommon(Object eventId, Object occurredAt, Object merchantId, Object storeId, Object orderId) {
        require(eventId != null, "eventId is required");
        require(occurredAt != null, "occurredAt is required");
        require(merchantId != null, "merchantId is required");
        require(storeId != null, "storeId is required");
        require(orderId != null, "orderId is required");
    }

    static void requireLines(List<ReserveStockCmd.Line> lines, int maxLines) {
        require(lines != null && !lines.isEmpty(), "a reserve needs at least one line");
        require(lines.size() <= maxLines, "a reserve has at most " + maxLines + " lines, got " + lines.size());
        Set<OrderItemId> seen = new HashSet<>();
        for (ReserveStockCmd.Line line : lines) {
            require(line != null, "a line is null");
            require(line.getOrderItemId() != null, "orderItemId is required");
            require(line.getProductId() != null, "productId is required");
            require(line.getSku() != null && line.getSku().getValue() != null && !line.getSku().getValue().isBlank(),
                    "sku is required");
            require(line.getSku().getValue().length() <= MAX_SKU_LENGTH, "sku is longer than " + MAX_SKU_LENGTH);
            require(line.getQuantity() >= 1, "quantity must be at least 1");
            require(seen.add(line.getOrderItemId()), "duplicate orderItemId " + line.getOrderItemId().getValue());
        }
    }

    static void requireRelease(Boolean restock, String reason) {
        require(restock != null, "restock is required");
        // The schema requires reason; a missing one is accepted on purpose (it is only copied into the movements).
        require(reason == null || reason.length() <= MAX_REASON_LENGTH, "reason is longer than " + MAX_REASON_LENGTH);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidStockCommandException(message);
        }
    }
}
