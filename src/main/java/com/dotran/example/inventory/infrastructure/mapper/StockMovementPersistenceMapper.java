package com.dotran.example.inventory.infrastructure.mapper;

import com.dotran.example.inventory.common.mapper.IdMapper;
import com.dotran.example.inventory.domain.model.StockMovement;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockMovementEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = "spring",
        uses = IdMapper.class
)
public abstract class StockMovementPersistenceMapper {

    @Autowired
    protected IdMapper idMapper;

    @Mapping(target = "id", expression = "java(idMapper.toStockMovementId(entity.getId()))")
    @Mapping(target = "tenantId", expression = "java(idMapper.toTenantId(entity.getTenantId()))")
    @Mapping(target = "storeId", expression = "java(idMapper.toStoreId(entity.getStoreId()))")
    @Mapping(target = "inventoryId", expression = "java(idMapper.toInventoryId(entity.getInventoryId()))")
    @Mapping(target = "referenceId", expression = "java(idMapper.toReferenceId(entity.getReferenceId()))")
    public abstract StockMovement fromEntity(StockMovementEntity entity);

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "tenantId", source = "tenantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "inventoryId", source = "inventoryId.value")
    @Mapping(target = "referenceId", source = "referenceId.value")
    public abstract StockMovementEntity fromStockMovement(StockMovement stockMovement);
}
