package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository {

    Inventory create(Inventory inventory);

    Inventory update(Inventory inventory);

    List<Inventory> updateBatch(List<Inventory> inventories);

    List<Inventory> createBatch(List<Inventory> inventories);

    Optional<Inventory> getById(InventoryId inventoryId);

    List<Inventory> getAllById(Collection<InventoryId> inventoryIds);

    List<Inventory> getByProductId(ProductId productId);

    List<Inventory> getByStoreIdAndSKUs(StoreId storeId, List<SKU> skus);

}
