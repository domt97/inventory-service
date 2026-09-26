package com.dotran.oms.inventory.application.usecase.inventory;

import com.dotran.oms.inventory.application.command.AdjustStockCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;

public interface AdjustStockUseCase {

    InventoryDetailDto adjust(AdjustStockCmd command);
}
