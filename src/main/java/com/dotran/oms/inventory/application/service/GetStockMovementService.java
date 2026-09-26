package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.dto.StockMovementDto;
import com.dotran.oms.inventory.application.mapper.StockMovementMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.usecase.movement.GetStockMovementsUseCase;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.exception.NotFoundException;
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
