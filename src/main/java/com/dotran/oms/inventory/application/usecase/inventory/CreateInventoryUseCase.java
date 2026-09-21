package com.dotran.oms.inventory.application.usecase.inventory;

import com.dotran.oms.inventory.application.command.CreateInventoryCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;

import java.util.List;

public interface CreateInventoryUseCase {

    List<InventoryDetailDto> create(CreateInventoryCmd command);
}
