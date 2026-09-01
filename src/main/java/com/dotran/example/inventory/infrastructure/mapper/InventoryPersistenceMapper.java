package com.dotran.example.inventory.infrastructure.mapper;

import com.dotran.example.inventory.common.mapper.IdMapper;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.infrastructure.persistence.entity.InventoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = "spring",
        uses = IdMapper.class
)
public abstract class InventoryPersistenceMapper {

    @Autowired
    protected IdMapper idMapper;

    @Mapping(target = "id", expression = "java(idMapper.toInventoryId(entity.getId()))")
    @Mapping(target = "tenantId", expression = "java(idMapper.toTenantId(entity.getTenantId()))")
    @Mapping(target = "storeId", expression = "java(idMapper.toStoreId(entity.getStoreId()))")
    @Mapping(target = "productId", expression = "java(idMapper.toProductId(entity.getStoreProductId()))")
    @Mapping(target = "sku", expression = "java(idMapper.toSKU(entity.getSku()))")
    public abstract Inventory fromEntity(InventoryEntity entity);


    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "tenantId", source = "tenantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "storeProductId", source = "productId.value")
    @Mapping(target = "sku", source = "sku.value")
    public abstract InventoryEntity fromInventory(Inventory inventory);
}
