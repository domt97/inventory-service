package com.dotran.example.inventory.infrastructure.persistence;

import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.common.annotation.PersistenceAdapter;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.infrastructure.mapper.InventoryPersistenceMapper;
import com.dotran.example.inventory.infrastructure.persistence.entity.InventoryEntity;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.example.inventory.infrastructure.persistence.jpa.SpringDataInventoryRepository;
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
}
