package com.dotran.example.inventory.infrastructure.mapper;

import com.dotran.example.inventory.common.mapper.IdMapper;
import com.dotran.example.inventory.domain.model.StockReservation;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = "spring",
        uses = IdMapper.class
)
public abstract class StockReservationPersistenceMapper {

    @Autowired
    protected IdMapper idMapper;

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "tenantId", source = "tenantId.value")
    @Mapping(target = "storeId", source = "storeId.value")
    @Mapping(target = "inventoryId", source = "inventoryId.value")
    @Mapping(target = "orderId", source = "orderId.value")
    @Mapping(target = "orderItemId", source = "orderItemId.value")
    public abstract StockReservationEntity toEntity(StockReservation stockReservation);

    @Mapping(target = "id", expression = "java(idMapper.toStockReservationId(entity.getId()))")
    @Mapping(target = "tenantId", expression = "java(idMapper.toTenantId(entity.getTenantId()))")
    @Mapping(target = "storeId", expression = "java(idMapper.toStoreId(entity.getStoreId()))")
    @Mapping(target = "inventoryId", expression = "java(idMapper.toInventoryId(entity.getInventoryId()))")
    @Mapping(target = "orderId", expression = "java(idMapper.toOrderId(entity.getOrderId()))")
    @Mapping(target = "orderItemId", expression = "java(idMapper.toOrderItemId(entity.getOrderItemId()))")
    public abstract StockReservation toDomain(StockReservationEntity entity);
}
