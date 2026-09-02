package com.dotran.example.inventory.application.usecase.movement;

import com.dotran.example.inventory.application.dto.StockMovementDto;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;

import java.util.List;

public interface GetStockMovementsUseCase {

    List<StockMovementDto> getStockMovements(InventoryId inventoryId);
}
