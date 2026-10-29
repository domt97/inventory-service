package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.infrastructure.mapper.InventoryPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.InventoryEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataInventoryRepository;
import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.exception.NotFoundException;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@PersistenceAdapter
@RequiredArgsConstructor
public class InventoryPersistenceAdapter implements InventoryRepository {

    private final SpringDataInventoryRepository springDataInventoryRepository;
    private final InventoryPersistenceMapper mapper;


    @Override
    public Inventory create(Inventory inventory) {
        InventoryEntity entity = mapper.fromInventory(inventory);

        InventoryEntity savedInventory = springDataInventoryRepository.saveAndFlush(entity);

        return mapper.fromEntity(savedInventory);
    }

    @Override
    public Inventory update(Inventory inventory) {
        InventoryEntity inventoryEntity = springDataInventoryRepository
                .findById(inventory.getId().getValue())
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        mapper.updateInventory(inventory, inventoryEntity);
        InventoryEntity savedInventory = springDataInventoryRepository.saveAndFlush(inventoryEntity);

        return mapper.fromEntity(savedInventory);
    }

    @Override
    public List<Inventory> updateBatch(List<Inventory> inventories) {
        Map<UUID, Inventory> inventoryMap = inventories
                .stream()
                .collect(Collectors.toMap(i -> i.getId().getValue(), Function.identity()));

        List<InventoryEntity> inventoryEntityList = springDataInventoryRepository
                .findAllById(inventoryMap.keySet());

        Set<UUID> foundIds = inventoryEntityList.stream()
                .map(InventoryEntity::getId)
                .collect(Collectors.toSet());

        if (!foundIds.equals(inventoryMap.keySet())) {
            Set<UUID> missingIds = new HashSet<>(inventoryMap.keySet());

            missingIds.removeAll(foundIds);

            throw new IllegalStateException("Inventory not found: " + missingIds);
        }

        for (InventoryEntity inventoryEntity : inventoryEntityList) {
            Inventory inventory = inventoryMap.get(inventoryEntity.getId());
            mapper.updateInventory(inventory, inventoryEntity);
        }

        List<InventoryEntity> savedInventoryEntities = springDataInventoryRepository
                .saveAll(inventoryEntityList);

        return savedInventoryEntities
                .stream()
                .map(mapper::fromEntity)
                .collect(toList());
    }

    @Override
    public List<Inventory> createBatch(List<Inventory> inventories) {
        List<InventoryEntity> inventoryEntityList = inventories
                .stream()
                .map(mapper::fromInventory)
                .collect(toList());

        List<InventoryEntity> savedInventoryEntities = springDataInventoryRepository
                .saveAllAndFlush(inventoryEntityList);

        return savedInventoryEntities
                .stream()
                .map(mapper::fromEntity)
                .collect(toList());
    }

    @Override
    public Optional<Inventory> getById(InventoryId inventoryId) {
        return springDataInventoryRepository
                .findById(inventoryId.getValue())
                .map(mapper::fromEntity);
    }

    @Override
    public List<Inventory> getAllById(Collection<InventoryId> inventoryIds) {
        return springDataInventoryRepository
                .findAllById(inventoryIds.stream().map(InventoryId::getValue).toList())
                .stream()
                .map(mapper::fromEntity)
                .collect(toList());
    }

    @Override
    public List<Inventory> getByProductId(ProductId productId) {
        return springDataInventoryRepository
                .findAllByStoreProductId(productId.getValue())
                .stream()
                .map(mapper::fromEntity)
                .collect(toList());
    }

    @Override
    public List<Inventory> getByStoreIdAndSKUs(StoreId storeId, List<SKU> skus) {
        return springDataInventoryRepository
                .findAllByStoreIdAndSkuIn(storeId.getValue(),
                        skus.stream().map(SKU::getValue).toList())
                .stream()
                .map(mapper::fromEntity)
                .collect(toList());
    }

    @Override
    public int reserve(InventoryId inventoryId, Long quantity) {
        return springDataInventoryRepository.reserve(inventoryId.getValue(), quantity);
    }

    @Override
    public int confirm(InventoryId inventoryId, Long quantity) {
        return springDataInventoryRepository.confirm(inventoryId.getValue(), quantity);
    }

    @Override
    public int release(InventoryId inventoryId, Long quantity) {
        return springDataInventoryRepository.release(inventoryId.getValue(), quantity);
    }

    @Override
    public int stockIn(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, Long quantity) {
        return springDataInventoryRepository.stockIn(inventoryId.getValue(), merchantId.getValue(), storeId.getValue(), quantity);
    }

    @Override
    public int stockOut(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, Long quantity) {
        return springDataInventoryRepository.stockOut(inventoryId.getValue(), merchantId.getValue(), storeId.getValue(), quantity);
    }

    @Override
    public int insertIfAbsent(Inventory inventory) {
        return springDataInventoryRepository.insertIfAbsent(inventory.getId().getValue(), inventory.getMerchantId().getValue(),
                inventory.getStoreId().getValue(), inventory.getProductId().getValue(), inventory.getSku().getValue(),
                inventory.getStatus().name());
    }

    @Override
    public int updateStatus(InventoryId inventoryId, MerchantId merchantId, StoreId storeId, ProductId productId,
                            InventoryStatus status) {
        return springDataInventoryRepository.updateStatus(inventoryId.getValue(), merchantId.getValue(), storeId.getValue(),
                productId.getValue(), status.name());
    }
}
