package com.dotran.example.inventory.infrastructure.mapper;

import com.dotran.example.inventory.common.mapper.InternalIdMapper;
import com.dotran.example.inventory.domain.model.StockMovement;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockMovementEntity;
import com.dotran.oms.core.mapper.IdMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = "spring",
        uses = InternalIdMapper.class
)
public abstract class StockMovementPersistenceMapper {

    @Autowired
    protected IdMapper idMapper;

    @Autowired
    protected InternalIdMapper internalIdMapper;

    @Mapping(target = "id", expression = "java(internalIdMapper.toStockMovementId(entity.getId()))")
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
