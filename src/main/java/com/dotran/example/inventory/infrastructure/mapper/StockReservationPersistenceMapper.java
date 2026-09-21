package com.dotran.example.inventory.infrastructure.mapper;

import com.dotran.example.inventory.common.mapper.InternalIdMapper;
import com.dotran.example.inventory.domain.model.StockReservation;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.oms.core.mapper.IdMapper;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = "spring",
        uses = {IdMapper.class, InternalIdMapper.class}
)
public abstract class StockReservationPersistenceMapper {

    @Autowired
    protected IdMapper idMapper;

    @Autowired
    protected InternalIdMapper internalIdMapper;

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "tenantId", source = "tenantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "inventoryId", source = "inventoryId.value")
    @Mapping(target = "orderId", source = "orderId.value")
    @Mapping(target = "orderItemId", source = "orderItemId.value")
    public abstract StockReservationEntity toEntity(StockReservation stockReservation);

    @Mapping(target = "id", expression = "java(internalIdMapper.toStockReservationId(entity.getId()))")
    @Mapping(target = "tenantId", expression = "java(idMapper.toTenantId(entity.getTenantId()))")
    @Mapping(target = "storeId", expression = "java(idMapper.toStoreId(entity.getStoreId()))")
    @Mapping(target = "inventoryId", expression = "java(idMapper.toInventoryId(entity.getInventoryId()))")
    @Mapping(target = "orderId", expression = "java(idMapper.toOrderId(entity.getOrderId()))")
    @Mapping(target = "orderItemId", expression = "java(idMapper.toOrderItemId(entity.getOrderItemId()))")
    public abstract StockReservation toDomain(StockReservationEntity entity);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "updatedAt", source = "updatedAt")
    public abstract void updateStatus(StockReservation stockReservation,
                                      @MappingTarget StockReservationEntity entity);
}
