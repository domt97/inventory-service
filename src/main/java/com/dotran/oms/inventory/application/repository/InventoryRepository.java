package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;

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

    /** Inventories of these SKUs in the store, scoped by merchant (empty input → empty result, no query). */
    List<Inventory> getByMerchantStoreAndSkus(MerchantId merchantId, StoreId storeId, Collection<SKU> skus);

    /**
     * Guarded reserve: {@code reserved += quantity} only when the row belongs to merchant and store, is ACTIVE
     * (BR-INV-08) and has enough available stock. 0 = refused.
     */
    int reserve(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, long quantity);

    /** Status and available quantity read with native SQL (not the persistence context); empty input → empty. */
    List<StockState> getStockStates(Collection<InventoryId> inventoryIds);

    int confirm(InventoryId inventoryId, Long quantity);

    int release(InventoryId inventoryId, Long quantity);

    int stockIn(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, Long quantity);

    int stockOut(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, Long quantity);

    /** Inserts the row unless (store, sku) already exists. Returns 1 if inserted, 0 otherwise. */
    int insertIfAbsent(Inventory inventory);

    /** Guarded status change of one row of the product; returns 0 if the row already has that status. */
    int updateStatus(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, ProductId productId,
                     InventoryStatus status);

}
