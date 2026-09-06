package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.domain.model.Inventory;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository {

    Inventory create(Inventory inventory);

    Inventory update(Inventory inventory);

    List<Inventory> updateBatch(List<Inventory> inventories);

    List<Inventory> createBatch(List<Inventory> inventories);

    Optional<Inventory> getById(InventoryId inventoryId);

    List<Inventory> getByProductId(ProductId productId);

    List<Inventory> getByStoreIdAndSKUs(StoreId storeId, List<SKU> skus);

}
