package com.dotran.example.inventory.infrastructure.rest.mapper;

import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.common.mapper.IdMapper;
import com.dotran.example.inventory.infrastructure.rest.dto.request.CreateInventoryRequest;
import com.dotran.example.inventory.infrastructure.rest.dto.response.InventoryDetailResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring", uses = {
        IdMapper.class
})
public abstract class InventoryRestMapper {

    @Autowired
    protected IdMapper idMapper;

    @Mapping(target = "tenantId", expression = "java(idMapper.toTenantId(request.getTenantId()))")
    @Mapping(target = "storeId", expression = "java(idMapper.toStoreId(request.getStoreId()))")
    @Mapping(target = "productId", expression = "java(idMapper.toProductId(request.getProductId()))")
    @Mapping(target = "sku", expression = "java(idMapper.toSKU(request.getSku()))")
    public abstract CreateInventoryCmd toCreateInventoryCmd(CreateInventoryRequest request);

    public abstract InventoryDetailResponse toInventoryDetailResponse(InventoryDetailDto inventoryDetailDto);
}
