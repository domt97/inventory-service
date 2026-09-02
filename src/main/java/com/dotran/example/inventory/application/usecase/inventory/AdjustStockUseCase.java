package com.dotran.example.inventory.application.usecase.inventory;

import com.dotran.example.inventory.application.command.AdjustStockCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;

public interface AdjustStockUseCase {

    InventoryDetailDto adjust(AdjustStockCmd command);
}
