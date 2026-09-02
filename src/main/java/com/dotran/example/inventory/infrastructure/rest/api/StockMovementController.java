package com.dotran.example.inventory.infrastructure.rest.api;

import com.dotran.example.inventory.application.mapper.StockMovementMapper;
import com.dotran.example.inventory.application.usecase.movement.GetStockMovementsUseCase;
import com.dotran.example.inventory.common.annotation.WebAdapter;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.infrastructure.rest.dto.response.InventoryDetailResponse;
import com.dotran.example.inventory.infrastructure.rest.dto.response.StockMovementResponse;
import com.dotran.example.inventory.infrastructure.rest.mapper.StockMovementRestMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Stock Movement Management", description = "APIs for managing Stock Movement lifecycle")
@WebAdapter
@RestController
@RequestMapping(value = "/v1/inventories/stock-movement")
@RequiredArgsConstructor
@Slf4j
public class StockMovementController {

    private final GetStockMovementsUseCase getStockMovementsUseCase;
    private final StockMovementRestMapper stockMovementRestMapper;

    @Operation(summary = "Get stock movement", description = "Retrieves the stock movement for the specified store product sku")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Stock movement retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = StockMovementResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
            @ApiResponse(responseCode = "404", description = "Inventory not found", content = @Content)
    })
    @GetMapping("/{inventoryId}")
    @ResponseStatus(HttpStatus.OK)
    public List<StockMovementResponse> getAllProductInventory(@PathVariable UUID inventoryId) {
        return stockMovementRestMapper.toStockMovementResponseList(getStockMovementsUseCase.getStockMovements(InventoryId.of(inventoryId)));
    }
}
