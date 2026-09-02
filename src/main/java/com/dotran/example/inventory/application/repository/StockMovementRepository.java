package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.domain.model.StockMovement;

import java.util.List;

public interface StockMovementRepository {

    StockMovement create(StockMovement stockMovement);

    List<StockMovement> getByInventoryId(InventoryId inventoryId);
}
