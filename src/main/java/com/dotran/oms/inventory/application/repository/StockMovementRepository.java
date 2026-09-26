package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.domain.id.InventoryId;

import java.util.List;

public interface StockMovementRepository {

    StockMovement create(StockMovement stockMovement);

    List<StockMovement> getByInventoryId(InventoryId inventoryId);
}
