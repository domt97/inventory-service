package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.dto.StockMovementDto;
import com.dotran.oms.inventory.application.mapper.StockMovementMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.common.id.StockMovementId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
import com.dotran.oms.core.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetStockMovementService")
class GetStockMovementServiceTest {

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private StockMovementMapper stockMovementMapper;

    @InjectMocks
    private GetStockMovementService getStockMovementService;

    private TenantId tenantId;
    private StoreId storeId;
    private InventoryId inventoryId;

    @BeforeEach
    void setUp() {
        tenantId = TenantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
    }

    private Inventory buildInventory() {
        return Inventory.builder()
                .id(inventoryId)
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(SKU.of("SKU-001"))
                .quantity(100L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    private StockMovement buildMovement(StockMovementType type, long quantity) {
        return StockMovement.builder()
                .id(new StockMovementId(1L))
                .tenantId(tenantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .type(type)
                .quantity(quantity)
                .build();
    }

    @Test
    @DisplayName("throws NotFoundException when inventory does not exist")
    void shouldThrowWhenInventoryNotFound() {
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> getStockMovementService.getStockMovements(inventoryId));
        verifyNoInteractions(stockMovementRepository, stockMovementMapper);
    }

    @Test
    @DisplayName("returns empty list when inventory has no stock movements")
    void shouldReturnEmptyWhenNoMovements() {
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(buildInventory()));
        when(stockMovementRepository.getByInventoryId(inventoryId)).thenReturn(Collections.emptyList());

        List<StockMovementDto> result = getStockMovementService.getStockMovements(inventoryId);

        assertTrue(result.isEmpty());
        verifyNoInteractions(stockMovementMapper);
    }

    @Test
    @DisplayName("returns mapped DTOs for all stock movements")
    void shouldReturnMappedMovements() {
        StockMovement m1 = buildMovement(StockMovementType.ADJUSTMENT_IN, 50L);
        StockMovement m2 = buildMovement(StockMovementType.SALE, 20L);
        StockMovementDto dto1 = mock(StockMovementDto.class);
        StockMovementDto dto2 = mock(StockMovementDto.class);

        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(buildInventory()));
        when(stockMovementRepository.getByInventoryId(inventoryId)).thenReturn(List.of(m1, m2));
        when(stockMovementMapper.toStockMovementDto(m1)).thenReturn(dto1);
        when(stockMovementMapper.toStockMovementDto(m2)).thenReturn(dto2);

        List<StockMovementDto> result = getStockMovementService.getStockMovements(inventoryId);

        assertEquals(2, result.size());
        verify(stockMovementMapper, times(2)).toStockMovementDto(any());
    }

    @Test
    @DisplayName("queries movements using the inventory's id from domain object")
    void shouldQueryMovementsByInventoryId() {
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(buildInventory()));
        when(stockMovementRepository.getByInventoryId(inventoryId)).thenReturn(Collections.emptyList());

        getStockMovementService.getStockMovements(inventoryId);

        verify(stockMovementRepository).getByInventoryId(inventoryId);
    }
}
