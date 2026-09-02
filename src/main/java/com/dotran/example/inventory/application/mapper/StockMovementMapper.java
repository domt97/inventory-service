package com.dotran.example.inventory.application.mapper;

import com.dotran.example.inventory.application.dto.StockMovementDto;
import com.dotran.example.inventory.domain.model.StockMovement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public abstract class StockMovementMapper {

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "tenantId", source = "tenantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "inventoryId", source = "inventoryId.value")
    @Mapping(target = "referenceId", source = "referenceId.value")
    public abstract StockMovementDto toStockMovementDto(StockMovement stockMovement);

    public abstract List<StockMovementDto> toStockMovementDtoList(List<StockMovement> stockMovements);
}
