package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("LoadInventoryService")
class LoadInventoryServiceTest {

    @Mock
    private InventoryRepository repository;

    @Mock
    private InventoryMapper mapper;

    @InjectMocks
    private LoadInventoryService loadInventoryService;

    private MerchantId merchantId;
    private StoreId storeId;
    private InventoryId inventoryId;
    private ProductId productId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
        productId = ProductId.of(UUID.randomUUID());
    }

    private Inventory buildInventory(InventoryId id) {
        return Inventory.builder()
                .id(id)
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(productId)
                .sku(SKU.of("SKU-001"))
                .quantity(100L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("loadById")
    class LoadById {

        @Test
        @DisplayName("returns mapped DTO when inventory exists")
        void shouldReturnDtoWhenFound() {
            Inventory inventory = buildInventory(inventoryId);
            InventoryDetailDto dto = mock(InventoryDetailDto.class);

            when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
            when(mapper.toDetailDto(inventory)).thenReturn(dto);

            InventoryDetailDto result = loadInventoryService.loadById(inventoryId);

            assertSame(dto, result);
            verify(mapper).toDetailDto(inventory);
        }

        @Test
        @DisplayName("throws NotFoundException when inventory does not exist")
        void shouldThrowWhenNotFound() {
            when(repository.getById(inventoryId)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> loadInventoryService.loadById(inventoryId));
            verifyNoInteractions(mapper);
        }
    }

    @Nested
    @DisplayName("loadByProductId")
    class LoadByProductId {

        @Test
        @DisplayName("returns mapped DTOs for all inventories matching product")
        void shouldReturnDtosWhenFound() {
            InventoryId id2 = InventoryId.of(UUID.randomUUID());
            Inventory inv1 = buildInventory(inventoryId);
            Inventory inv2 = buildInventory(id2);
            InventoryDetailDto dto1 = mock(InventoryDetailDto.class);
            InventoryDetailDto dto2 = mock(InventoryDetailDto.class);

            when(repository.getByProductId(productId)).thenReturn(List.of(inv1, inv2));
            when(mapper.toDetailDto(inv1)).thenReturn(dto1);
            when(mapper.toDetailDto(inv2)).thenReturn(dto2);

            List<InventoryDetailDto> result = loadInventoryService.loadByProductId(productId);

            assertEquals(2, result.size());
            verify(mapper, times(2)).toDetailDto(any());
        }

        @Test
        @DisplayName("throws NotFoundException when no inventories found for product")
        void shouldThrowWhenNoneFound() {
            when(repository.getByProductId(productId)).thenReturn(Collections.emptyList());

            assertThrows(NotFoundException.class,
                    () -> loadInventoryService.loadByProductId(productId));
            verifyNoInteractions(mapper);
        }
    }
}
