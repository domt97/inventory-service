package com.dotran.example.inventory.application.usecase.inventory;

import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;

import java.util.List;

public interface LoadInventoryUseCase {

    InventoryDetailDto loadById(InventoryId id);

    List<InventoryDetailDto> loadByProductId(ProductId productId);
}
