package com.dotran.oms.inventory.infrastructure.rest.internal;

import com.dotran.oms.inventory.application.command.CreateInventoryCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.oms.inventory.infrastructure.rest.dto.request.CreateInventoryRequest;
import com.dotran.oms.inventory.infrastructure.rest.dto.response.InventoryDetailResponse;
import com.dotran.oms.inventory.infrastructure.rest.mapper.InventoryRestMapper;
import com.dotran.oms.core.annotation.WebAdapter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Hidden
@WebAdapter
@RestController
@RequestMapping("/internal/v1/inventory-service/inventories")
@RequiredArgsConstructor
public class InventoryInternalController {

    private final CreateInventoryUseCase createInventoryUseCase;
    private final InventoryRestMapper restMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<InventoryDetailResponse> create(@RequestBody @Valid CreateInventoryRequest request) {
        CreateInventoryCmd cmd = restMapper.toCreateInventoryCmd(request);
        List<InventoryDetailDto> result = createInventoryUseCase.create(cmd);
        return result.stream().map(restMapper::toInventoryDetailResponse).toList();
    }
}
