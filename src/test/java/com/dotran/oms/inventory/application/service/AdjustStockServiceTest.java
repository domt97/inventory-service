package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.command.AdjustStockCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.common.id.StockMovementId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.exception.NotFoundException;
import com.dotran.oms.core.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjustStockService")
class AdjustStockServiceTest {

    @Mock
    private InventoryRepository repository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private InventoryMapper inventoryMapper;

    @InjectMocks
    private AdjustStockService adjustStockService;

    private MerchantId merchantId;
    private StoreId storeId;
    private InventoryId inventoryId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
    }

    private Inventory buildInventory(long quantity, long reservedQuantity) {
        return Inventory.builder()
                .id(inventoryId)
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(SKU.of("SKU-001"))
                .quantity(quantity)
                .reservedQuantity(reservedQuantity)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    private StockMovement buildSavedMovement() {
        return StockMovement.builder()
                .id(new StockMovementId(1L))
                .merchantId(merchantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .type(StockMovementType.ADJUSTMENT_IN)
                .quantity(10L)
                .build();
    }

    private AdjustStockCmd buildCmd(StockMovementType type, long quantity) {
        return AdjustStockCmd.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .movementType(type)
                .quantity(quantity)
                .reason("Test adjustment")
                .build();
    }

    @Test
    @DisplayName("STOCK_IN calls stockIn repository and returns updated inventory")
    void shouldCallStockInForStockInType() {
        Inventory inventory = buildInventory(100L, 20L);
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_IN, 50L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(repository.stockIn(inventoryId, merchantId, storeId, 50L)).thenReturn(1);
        when(stockMovementRepository.create(any(StockMovement.class))).thenReturn(buildSavedMovement());
        when(inventoryMapper.toDetailDto(any())).thenReturn(mock(InventoryDetailDto.class));

        adjustStockService.adjust(cmd);

        verify(repository).stockIn(inventoryId, merchantId, storeId, 50L);
        verify(repository, never()).stockOut(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("STOCK_OUT calls stockOut repository when sufficient available stock")
    void shouldCallStockOutForStockOutType() {
        Inventory inventory = buildInventory(100L, 20L); // available = 80
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_OUT, 50L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(repository.stockOut(inventoryId, merchantId, storeId, 50L)).thenReturn(1);
        when(stockMovementRepository.create(any(StockMovement.class))).thenReturn(buildSavedMovement());
        when(inventoryMapper.toDetailDto(any())).thenReturn(mock(InventoryDetailDto.class));

        adjustStockService.adjust(cmd);

        verify(repository).stockOut(inventoryId, merchantId, storeId, 50L);
        verify(repository, never()).stockIn(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("unknown merchant on an existing inventory → ValidationException, no stock change, no movement")
    void shouldRejectUnknownMerchant() {
        MerchantId otherMerchant = MerchantId.of(UUID.randomUUID());
        AdjustStockCmd cmd = AdjustStockCmd.builder()
                .merchantId(otherMerchant)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .movementType(StockMovementType.ADJUSTMENT_IN)
                .quantity(10L)
                .reason("Test adjustment")
                .build();

        when(repository.getById(inventoryId)).thenReturn(Optional.of(buildInventory(100L, 0L)));

        assertThrows(ValidationException.class, () -> adjustStockService.adjust(cmd));
        verify(repository, never()).stockIn(any(), any(), any(), anyLong());
        verify(repository, never()).stockOut(any(), any(), any(), anyLong());
        verifyNoInteractions(stockMovementRepository);
    }

    @Test
    @DisplayName("throws NotFoundException when inventory does not exist")
    void shouldThrowWhenInventoryNotFound() {
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_IN, 10L);

        when(repository.getById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> adjustStockService.adjust(cmd));
    }

    @Test
    @DisplayName("throws InsufficientStockException when STOCK_OUT atomic SQL guard fails")
    void shouldThrowWhenStockOutGuardFails() {
        Inventory inventory = buildInventory(100L, 90L); // available = 10
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_OUT, 50L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));

        assertThrows(InsufficientStockException.class, () -> adjustStockService.adjust(cmd));
        verify(repository, never()).stockOut(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("creates stock movement record after successful adjustment")
    void shouldCreateStockMovementAfterAdjust() {
        Inventory inventory = buildInventory(100L, 0L);
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_IN, 50L);
        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(repository.stockIn(inventoryId, merchantId, storeId, 50L)).thenReturn(1);
        when(stockMovementRepository.create(any(StockMovement.class))).thenReturn(buildSavedMovement());
        when(inventoryMapper.toDetailDto(any())).thenReturn(mock(InventoryDetailDto.class));

        adjustStockService.adjust(cmd);

        verify(stockMovementRepository).create(any(StockMovement.class));
    }

    @Test
    @DisplayName("does not reload inventory after atomic SQL — uses in-memory state for response")
    void shouldNotReloadInventoryAfterAtomicSql() {
        Inventory inventory = buildInventory(100L, 0L);
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_IN, 50L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(repository.stockIn(inventoryId, merchantId, storeId, 50L)).thenReturn(1);
        when(stockMovementRepository.create(any())).thenReturn(buildSavedMovement());
        when(inventoryMapper.toDetailDto(any())).thenReturn(mock(InventoryDetailDto.class));

        adjustStockService.adjust(cmd);

        verify(repository, times(1)).getById(inventoryId);
    }

    @Test
    @DisplayName("throws ValidationException when inventory belongs to a different merchant or store")
    void shouldThrowWhenMerchantOrStoreMismatch() {
        // inventory belongs to a different store
        Inventory inventory = Inventory.builder()
                .id(inventoryId)
                .merchantId(merchantId)
                .storeId(StoreId.of(UUID.randomUUID())) // different store
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(SKU.of("SKU-001"))
                .quantity(100L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();

        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_IN, 10L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));

        assertThrows(ValidationException.class, () -> adjustStockService.adjust(cmd));
        verify(repository, never()).stockIn(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("throws InsufficientStockException when STOCK_IN atomic SQL returns 0 (concurrent race)")
    void shouldThrowWhenStockInAtomicSqlReturnsZero() {
        Inventory inventory = buildInventory(100L, 0L);
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_IN, 50L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(repository.stockIn(inventoryId, merchantId, storeId, 50L)).thenReturn(0);

        assertThrows(InsufficientStockException.class, () -> adjustStockService.adjust(cmd));
        verify(stockMovementRepository, never()).create(any());
    }

    @Test
    @DisplayName("throws InsufficientStockException when STOCK_OUT atomic SQL returns 0 (concurrent race)")
    void shouldThrowWhenStockOutAtomicSqlReturnsZero() {
        Inventory inventory = buildInventory(100L, 20L); // available=80, domain passes
        AdjustStockCmd cmd = buildCmd(StockMovementType.ADJUSTMENT_OUT, 50L);

        when(repository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(repository.stockOut(inventoryId, merchantId, storeId, 50L)).thenReturn(0);

        assertThrows(InsufficientStockException.class, () -> adjustStockService.adjust(cmd));
        verify(stockMovementRepository, never()).create(any());
    }
}
