package com.dotran.oms.inventory.infrastructure.rest.api;

import com.dotran.oms.inventory.application.dto.StockMovementDto;
import com.dotran.oms.inventory.application.usecase.movement.GetStockMovementsUseCase;
import com.dotran.oms.inventory.infrastructure.rest.dto.response.StockMovementResponse;
import com.dotran.oms.inventory.infrastructure.rest.mapper.StockMovementRestMapper;
import com.dotran.oms.core.domain.id.InventoryId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockMovementController")
class StockMovementControllerTest {

    @Mock
    private GetStockMovementsUseCase getStockMovementsUseCase;

    @Mock
    private StockMovementRestMapper stockMovementRestMapper;

    @InjectMocks
    private StockMovementController controller;

    @Test
    @DisplayName("getAllProductInventory — wraps UUID into InventoryId, calls use case, maps result")
    void shouldGetStockMovements() {
        UUID inventoryUUID = UUID.randomUUID();
        StockMovementDto dto = mock(StockMovementDto.class);
        StockMovementResponse response = mock(StockMovementResponse.class);

        when(getStockMovementsUseCase.getStockMovements(InventoryId.of(inventoryUUID))).thenReturn(List.of(dto));
        when(stockMovementRestMapper.toStockMovementResponseList(List.of(dto))).thenReturn(List.of(response));

        List<StockMovementResponse> result = controller.getAllProductInventory(inventoryUUID);

        assertEquals(1, result.size());
        assertSame(response, result.get(0));
        verify(getStockMovementsUseCase).getStockMovements(InventoryId.of(inventoryUUID));
    }

    @Test
    @DisplayName("returns empty list when no movements exist for inventory")
    void shouldReturnEmptyWhenNoMovements() {
        UUID inventoryUUID = UUID.randomUUID();

        when(getStockMovementsUseCase.getStockMovements(InventoryId.of(inventoryUUID))).thenReturn(List.of());
        when(stockMovementRestMapper.toStockMovementResponseList(List.of())).thenReturn(List.of());

        List<StockMovementResponse> result = controller.getAllProductInventory(inventoryUUID);

        assertTrue(result.isEmpty());
    }
}
