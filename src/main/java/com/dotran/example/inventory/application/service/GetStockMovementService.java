package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.dto.StockMovementDto;
import com.dotran.example.inventory.application.mapper.StockMovementMapper;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.repository.StockMovementRepository;
import com.dotran.example.inventory.application.usecase.movement.GetStockMovementsUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.common.utils.CollectionUtils;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.domain.model.StockMovement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class GetStockMovementService implements GetStockMovementsUseCase {

    private final StockMovementRepository stockMovementRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementMapper stockMovementMapper;

    @Override
    @Transactional
    public List<StockMovementDto> getStockMovements(InventoryId inventoryId) {
        Inventory inventory = inventoryRepository.getById(inventoryId)
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        List<StockMovement> stockMovements = stockMovementRepository.getByInventoryId(inventory.getId());

        if (CollectionUtils.isEmpty(stockMovements)) {
            log.warn("There is no stock movement for inventoryId: {}", inventoryId.getValue());
            return List.of();
        }

        return stockMovements.stream()
                .map(stockMovementMapper::toStockMovementDto)
                .toList();
    }
}
