package com.dotran.example.inventory.infrastructure.rest.mapper;

import com.dotran.example.inventory.application.dto.StockMovementDto;
import com.dotran.example.inventory.common.mapper.InternalIdMapper;
import com.dotran.example.inventory.infrastructure.rest.dto.response.StockMovementResponse;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring", uses = {
        InternalIdMapper.class
})
public abstract class StockMovementRestMapper {

    @Autowired
    protected InternalIdMapper internalIdMapper;

    public abstract StockMovementResponse toStockMovementResponse(StockMovementDto dto);

    public abstract List<StockMovementResponse> toStockMovementResponseList(List<StockMovementDto> dtoList);
}
