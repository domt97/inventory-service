package com.dotran.example.inventory.infrastructure.rest.mapper;

import com.dotran.example.inventory.application.dto.StockMovementDto;
import com.dotran.example.inventory.common.mapper.IdMapper;
import com.dotran.example.inventory.infrastructure.rest.dto.response.StockMovementResponse;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring", uses = {
        IdMapper.class
})
public abstract class StockMovementRestMapper {

    @Autowired
    protected IdMapper idMapper;

    public abstract StockMovementResponse toStockMovementResponse(StockMovementDto dto);

    public abstract List<StockMovementResponse> toStockMovementResponseList(List<StockMovementDto> dtoList);
}
