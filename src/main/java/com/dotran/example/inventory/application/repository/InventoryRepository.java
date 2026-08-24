package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.domain.model.Inventory;

public interface InventoryRepository {

    Inventory create(Inventory inventory);

    Inventory getById(InventoryId inventoryId);

}
