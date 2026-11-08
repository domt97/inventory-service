package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;

/** Status and available quantity of one inventory row, read without the persistence context. */
public record StockState(InventoryId inventoryId, InventoryStatus status, long available) {
}
