package com.dotran.example.inventory.application.usecase.inventory;

import com.dotran.example.inventory.application.command.CreateInventoryCommand;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;

public interface CreateInventoryUseCase {

    InventoryDetailDto create(CreateInventoryCommand command);
}
