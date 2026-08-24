package com.dotran.example.inventory.application.mapper;

import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.domain.model.Inventory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class InventoryMapper {

    public abstract Inventory fromCreatedCmd(CreateInventoryCmd cmd);

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "tenantId", source = "tenantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "productId", source = "productId.value")
    @Mapping(target = "sku", source = "sku.value")
    public abstract InventoryDetailDto toDetailDto(Inventory inventory);
}
