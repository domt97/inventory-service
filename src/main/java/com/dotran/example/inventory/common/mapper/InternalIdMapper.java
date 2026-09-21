package com.dotran.example.inventory.common.mapper;

import com.dotran.example.inventory.common.domain.valueobject.StockMovementId;
import com.dotran.example.inventory.common.domain.valueobject.StockReservationId;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface InternalIdMapper {

    @Named("toStockMovementId")
    default StockMovementId toStockMovementId(Long id) {
        return id == null ? null : new StockMovementId(id);
    }

    @Named("toStockReservationId")
    default StockReservationId toStockReservationId(Long id) {
        return id == null ? null : new StockReservationId(id);
    }
}
