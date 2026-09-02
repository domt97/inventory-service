package com.dotran.example.inventory.infrastructure.rest.api;

import com.dotran.example.inventory.application.command.AdjustStockCmd;
import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.application.usecase.inventory.AdjustStockUseCase;
import com.dotran.example.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.example.inventory.application.usecase.inventory.LoadInventoryUseCase;
import com.dotran.example.inventory.common.annotation.WebAdapter;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.infrastructure.rest.dto.request.AdjustStockRequest;
import com.dotran.example.inventory.infrastructure.rest.dto.request.CreateInventoryRequest;
import com.dotran.example.inventory.infrastructure.rest.dto.response.InventoryDetailResponse;
import com.dotran.example.inventory.infrastructure.rest.mapper.InventoryRestMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Inventory Management", description = "APIs for managing inventory lifecycle")
@WebAdapter
@RestController
@RequestMapping(value = "/v1/inventories")
@RequiredArgsConstructor
@Slf4j
public class InventoryController {

    private final CreateInventoryUseCase createInventoryUseCase;
    private final AdjustStockUseCase adjustStockUseCase;
    private final LoadInventoryUseCase loadInventoryUseCase;
    private final InventoryRestMapper restMapper;

    @Operation(summary = "Create a new inventory", description = "Creates a new inventory for the specified store product sku")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Inventory created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
            @ApiResponse(responseCode = "409", description = "Inventory already exists", content = @Content)
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<InventoryDetailResponse> create(
            @RequestBody @Valid CreateInventoryRequest createInventoryRequest) {
        CreateInventoryCmd createInventoryCmd = restMapper.toCreateInventoryCmd(createInventoryRequest);
        List<InventoryDetailDto> inventoryDetailDtoList = createInventoryUseCase.create(createInventoryCmd);

        return inventoryDetailDtoList.stream()
                .map(restMapper::toInventoryDetailResponse)
                .toList();
    }

    @Operation(summary = "Adjust stock", description = "Adjusts the stock for the specified inventory")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Inventory adjusted successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
            @ApiResponse(responseCode = "409", description = "Inventory already exists", content = @Content)
    })
    @PostMapping("/{inventoryId}")
    @ResponseStatus(HttpStatus.OK)
    public InventoryDetailResponse adjustStock(
            @PathVariable UUID inventoryId,
            @RequestBody @Valid AdjustStockRequest adjustStockRequest) {
        AdjustStockCmd adjustStockCmd = restMapper.toAdjustStockCmd(inventoryId, adjustStockRequest);
        InventoryDetailDto inventoryDetailDto = adjustStockUseCase.adjust(adjustStockCmd);

        return restMapper.toInventoryDetailResponse(inventoryDetailDto);
    }

    @Operation(summary = "Get inventory", description = "Retrieves the inventory for the specified store product sku")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Inventory retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
            @ApiResponse(responseCode = "404", description = "Inventory not found", content = @Content)
    })
    @GetMapping("/{inventoryId}")
    @ResponseStatus(HttpStatus.OK)
    public InventoryDetailResponse getInventory(@PathVariable UUID inventoryId) {
        return restMapper.toInventoryDetailResponse(loadInventoryUseCase.loadById(InventoryId.of(inventoryId)));
    }

    @Operation(summary = "Get inventory", description = "Retrieves the inventory for the specified store product sku")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Inventory retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
            @ApiResponse(responseCode = "404", description = "Inventory not found", content = @Content)
    })
    @GetMapping("/products/{productId}")
    @ResponseStatus(HttpStatus.OK)
    public List<InventoryDetailResponse> getAllProductInventory(@PathVariable UUID productId) {
        return restMapper.toInventoryDetailResponseList(loadInventoryUseCase.loadByProductId(ProductId.of(productId)));
    }
}
