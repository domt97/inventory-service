package com.dotran.oms.inventory.application.usecase.movement;

import com.dotran.oms.inventory.application.dto.StockMovementDto;
import com.dotran.oms.core.domain.id.InventoryId;

import java.util.List;

public interface GetStockMovementsUseCase {

    List<StockMovementDto> getStockMovements(InventoryId inventoryId);
}
