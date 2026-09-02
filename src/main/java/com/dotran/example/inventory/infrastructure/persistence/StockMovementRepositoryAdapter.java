package com.dotran.example.inventory.infrastructure.persistence;

import com.dotran.example.inventory.application.repository.StockMovementRepository;
import com.dotran.example.inventory.common.annotation.PersistenceAdapter;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.domain.model.StockMovement;
import com.dotran.example.inventory.infrastructure.mapper.StockMovementPersistenceMapper;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockMovementEntity;
import com.dotran.example.inventory.infrastructure.persistence.jpa.SpringDataStockMovementRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;

@PersistenceAdapter
@RequiredArgsConstructor
public class StockMovementRepositoryAdapter implements StockMovementRepository {

    private final SpringDataStockMovementRepository repository;
    private final StockMovementPersistenceMapper mapper;

    @Override
    public StockMovement create(StockMovement stockMovement) {
        StockMovementEntity stockMovementEntity = mapper.fromStockMovement(stockMovement);

        StockMovementEntity savedStockMovement = repository.saveAndFlush(stockMovementEntity);

        return mapper.fromEntity(savedStockMovement);
    }

    @Override
    public List<StockMovement> getByInventoryId(InventoryId inventoryId) {
        return repository.findAllByInventoryId(inventoryId.getValue())
                .stream()
                .map(mapper::fromEntity)
                .toList();
    }
}
