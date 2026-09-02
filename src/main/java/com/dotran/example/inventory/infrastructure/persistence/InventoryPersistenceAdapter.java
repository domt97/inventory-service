package com.dotran.example.inventory.infrastructure.persistence;

import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.common.annotation.PersistenceAdapter;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.infrastructure.mapper.InventoryPersistenceMapper;
import com.dotran.example.inventory.infrastructure.persistence.entity.InventoryEntity;
import com.dotran.example.inventory.infrastructure.persistence.jpa.SpringDataInventoryRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@PersistenceAdapter
@RequiredArgsConstructor
public class InventoryPersistenceAdapter implements InventoryRepository {

    private final SpringDataInventoryRepository springDataInventoryRepository;
    private final InventoryPersistenceMapper mapper;


    @Override
    public Inventory save(Inventory inventory) {
        InventoryEntity entity = mapper.fromInventory(inventory);

        InventoryEntity savedInventory = springDataInventoryRepository.saveAndFlush(entity);

        return mapper.fromEntity(savedInventory);
    }

    @Override
    public List<Inventory> saveList(List<Inventory> inventories) {
        List<InventoryEntity> inventoryEntityList = inventories
                .stream()
                .map(mapper::fromInventory)
                .collect(Collectors.toList());

        List<InventoryEntity> savedInventoryEntities = springDataInventoryRepository
                .saveAll(inventoryEntityList);

        return savedInventoryEntities
                .stream()
                .map(mapper::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Inventory> getById(InventoryId inventoryId) {
        return springDataInventoryRepository
                .findById(inventoryId.getValue())
                .map(mapper::fromEntity);
    }
}
