package com.dotran.example.inventory.application.usecase.movement;

import com.dotran.example.inventory.application.dto.StockMovementDto;
import com.dotran.oms.core.domain.id.InventoryId;

import java.util.List;

public interface GetStockMovementsUseCase {

    List<StockMovementDto> getStockMovements(InventoryId inventoryId);
}
