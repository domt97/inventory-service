package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.domain.model.Inventory;

import java.util.Optional;

public interface InventoryRepository {

    Inventory save(Inventory inventory);

    Optional<Inventory> getById(InventoryId inventoryId);

}
