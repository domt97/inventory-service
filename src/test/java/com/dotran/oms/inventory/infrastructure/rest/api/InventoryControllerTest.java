package com.dotran.oms.inventory.infrastructure.rest.api;

import com.dotran.oms.inventory.application.command.AdjustStockCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.usecase.inventory.AdjustStockUseCase;
import com.dotran.oms.inventory.application.usecase.inventory.LoadInventoryUseCase;
import com.dotran.oms.inventory.infrastructure.rest.dto.request.AdjustStockRequest;
import com.dotran.oms.inventory.infrastructure.rest.dto.response.InventoryDetailResponse;
import com.dotran.oms.inventory.infrastructure.rest.mapper.InventoryRestMapper;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryController")
class InventoryControllerTest {

    @Mock
    private AdjustStockUseCase adjustStockUseCase;

    @Mock
    private LoadInventoryUseCase loadInventoryUseCase;

    @Mock
    private InventoryRestMapper restMapper;

    @InjectMocks
    private InventoryController controller;

    @Test
    @DisplayName("adjustStock — maps request and path id to command, calls use case, maps result")
    void shouldAdjustStock() {
        UUID inventoryUUID = UUID.randomUUID();
        AdjustStockRequest request = mock(AdjustStockRequest.class);
        AdjustStockCmd cmd = mock(AdjustStockCmd.class);
        InventoryDetailDto dto = mock(InventoryDetailDto.class);
        InventoryDetailResponse response = mock(InventoryDetailResponse.class);

        when(restMapper.toAdjustStockCmd(inventoryUUID, request)).thenReturn(cmd);
        when(adjustStockUseCase.adjust(cmd)).thenReturn(dto);
        when(restMapper.toInventoryDetailResponse(dto)).thenReturn(response);

        InventoryDetailResponse result = controller.adjustStock(inventoryUUID, request);

        assertSame(response, result);
        verify(adjustStockUseCase).adjust(cmd);
    }

    @Test
    @DisplayName("getInventory — calls loadById with wrapped InventoryId, maps result")
    void shouldGetInventory() {
        UUID inventoryUUID = UUID.randomUUID();
        InventoryDetailDto dto = mock(InventoryDetailDto.class);
        InventoryDetailResponse response = mock(InventoryDetailResponse.class);

        when(loadInventoryUseCase.loadById(InventoryId.of(inventoryUUID))).thenReturn(dto);
        when(restMapper.toInventoryDetailResponse(dto)).thenReturn(response);

        InventoryDetailResponse result = controller.getInventory(inventoryUUID);

        assertSame(response, result);
        verify(loadInventoryUseCase).loadById(InventoryId.of(inventoryUUID));
    }

    @Test
    @DisplayName("getAllProductInventory — calls loadByProductId with wrapped ProductId, maps result list")
    void shouldGetAllProductInventory() {
        UUID productUUID = UUID.randomUUID();
        InventoryDetailDto dto = mock(InventoryDetailDto.class);
        InventoryDetailResponse response = mock(InventoryDetailResponse.class);

        when(loadInventoryUseCase.loadByProductId(ProductId.of(productUUID))).thenReturn(List.of(dto));
        when(restMapper.toInventoryDetailResponseList(List.of(dto))).thenReturn(List.of(response));

        List<InventoryDetailResponse> result = controller.getAllProductInventory(productUUID);

        assertEquals(1, result.size());
        assertSame(response, result.get(0));
        verify(loadInventoryUseCase).loadByProductId(ProductId.of(productUUID));
    }
}
