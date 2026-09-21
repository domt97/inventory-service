package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.inventory.infrastructure.mapper.StockMovementPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.StockMovementEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockMovementRepository;
import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.InventoryId;
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
