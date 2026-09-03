package com.dotran.example.inventory.application.mapper;

import com.dotran.example.inventory.application.command.ReserveStockCmd;
import com.dotran.example.inventory.domain.model.StockReservation;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class StockReservationMapper {

    public abstract StockReservation toDomain(ReserveStockCmd cmd);
}
