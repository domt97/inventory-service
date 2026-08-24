package com.dotran.example.inventory.infrastructure.persistence;

import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.common.annotation.PersistenceAdapter;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.infrastructure.mapper.InventoryPersistenceMapper;
import com.dotran.example.inventory.infrastructure.persistence.entity.InventoryEntity;
import com.dotran.example.inventory.infrastructure.persistence.jpa.SpringDataInventoryRepository;
import lombok.RequiredArgsConstructor;

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
    public Inventory getById(InventoryId inventoryId) {
        return springDataInventoryRepository
                .findById(inventoryId.getValue())
                .map(mapper::fromEntity)
                .orElseThrow(() -> new NotFoundException("Inventory not found"));
    }
}
