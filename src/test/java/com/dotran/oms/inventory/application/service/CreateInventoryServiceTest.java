package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.command.CreateInventoryCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateInventoryService")
class CreateInventoryServiceTest {

    @Mock
    private InventoryRepository repository;

    @Mock
    private InventoryMapper mapper;

    @InjectMocks
    private CreateInventoryService createInventoryService;

    private TenantId tenantId;
    private StoreId storeId;
    private ProductId productId;

    @BeforeEach
    void setUp() {
        tenantId = TenantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        productId = ProductId.of(UUID.randomUUID());
    }

    @Test
    @DisplayName("creates one inventory per SKU with initialized state")
    void shouldCreateInventoryPerSku() {
        SKU sku1 = SKU.of("SKU-001");
        SKU sku2 = SKU.of("SKU-002");

        CreateInventoryCmd cmd = CreateInventoryCmd.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(productId)
                .skus(List.of(sku1, sku2))
                .build();

        Inventory saved1 = buildSavedInventory(sku1);
        Inventory saved2 = buildSavedInventory(sku2);

        when(repository.createBatch(any())).thenReturn(List.of(saved1, saved2));
        when(mapper.toDetailDto(any())).thenReturn(mock(InventoryDetailDto.class));

        List<InventoryDetailDto> result = createInventoryService.create(cmd);

        assertEquals(2, result.size());
        verify(mapper, times(2)).toDetailDto(any());
    }

    @Test
    @DisplayName("initializes each inventory with zero quantity and ACTIVE status")
    void shouldInitializeInventoriesWithZeroQuantity() {
        SKU sku = SKU.of("SKU-001");

        CreateInventoryCmd cmd = CreateInventoryCmd.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(productId)
                .skus(List.of(sku))
                .build();

        ArgumentCaptor<List<Inventory>> captor = ArgumentCaptor.forClass(List.class);
        when(repository.createBatch(captor.capture())).thenReturn(List.of(buildSavedInventory(sku)));
        when(mapper.toDetailDto(any())).thenReturn(mock(InventoryDetailDto.class));

        createInventoryService.create(cmd);

        List<Inventory> created = captor.getValue();
        assertEquals(1, created.size());

        Inventory inventory = created.get(0);
        assertEquals(0L, inventory.getQuantity());
        assertEquals(0L, inventory.getReservedQuantity());
        assertEquals(InventoryStatus.ACTIVE, inventory.getStatus());
        assertEquals(sku, inventory.getSku());
        assertEquals(tenantId, inventory.getTenantId());
        assertEquals(storeId, inventory.getStoreId());
    }

    @Test
    @DisplayName("returns empty list when no SKUs provided")
    void shouldReturnEmptyWhenNoSkus() {
        CreateInventoryCmd cmd = CreateInventoryCmd.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(productId)
                .skus(List.of())
                .build();

        when(repository.createBatch(any())).thenReturn(List.of());

        List<InventoryDetailDto> result = createInventoryService.create(cmd);

        assertTrue(result.isEmpty());
        verifyNoInteractions(mapper);
    }

    private Inventory buildSavedInventory(SKU sku) {
        return Inventory.builder()
                .id(InventoryId.of(UUID.randomUUID()))
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(productId)
                .sku(sku)
                .quantity(0L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();
    }
}
