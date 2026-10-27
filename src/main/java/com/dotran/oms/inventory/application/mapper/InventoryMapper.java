package com.dotran.oms.inventory.application.mapper;

import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.domain.model.Inventory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class InventoryMapper {

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "merchantId", source = "merchantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "productId", source = "productId.value")
    @Mapping(target = "sku", source = "sku.value")
    @Mapping(target = "availableQuantity", expression = "java(inventory.availableQuantity())")
    public abstract InventoryDetailDto toDetailDto(Inventory inventory);
}
