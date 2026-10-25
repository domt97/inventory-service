package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReserveStockService")
class ReserveStockServiceTest {

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private ReserveStockService reserveStockService;

    private MerchantId merchantId;
    private StoreId storeId;
    private OrderId orderId;
    private InventoryId inventoryId;
    private SKU sku;

    @BeforeEach
    void setUp() {
        merchantId = MerchantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        orderId = OrderId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
        sku = SKU.of("SKU-001");
    }

    private Inventory buildInventory(long quantity, long reservedQuantity) {
        return Inventory.builder()
                .id(inventoryId)
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(sku)
                .quantity(quantity)
                .reservedQuantity(reservedQuantity)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    private ReserveStockCmd buildCmd(long quantity) {
        return ReserveStockCmd.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .skus(List.of(ReserveStockCmd.ReserveStockSKU.builder()
                        .sku(sku)
                        .orderItemId(OrderItemId.of(UUID.randomUUID()))
                        .productId(ProductId.of(UUID.randomUUID()))
                        .quantity(quantity)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("creates reservations when stock is sufficient")
    void shouldCreateReservationsWhenStockSufficient() {
        Inventory inventory = buildInventory(100L, 0L);
        ReserveStockCmd cmd = buildCmd(30L);

        when(inventoryRepository.getByStoreIdAndSKUs(storeId, List.of(sku)))
                .thenReturn(List.of(inventory));
        when(inventoryRepository.reserve(inventoryId, 30L)).thenReturn(1);
        when(stockReservationRepository.createBatch(any())).thenReturn(List.of());

        reserveStockService.reserve(cmd);

        verify(inventoryRepository).reserve(inventoryId, 30L);
        verify(stockReservationRepository).createBatch(any());
    }

    @Test
    @DisplayName("returns early when SKU list is empty")
    void shouldReturnEarlyWhenNoSkus() {
        ReserveStockCmd cmd = ReserveStockCmd.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .skus(Collections.emptyList())
                .build();

        reserveStockService.reserve(cmd);

        verifyNoInteractions(inventoryRepository, stockReservationRepository);
    }

    @Test
    @DisplayName("throws ValidationException when no inventories found for store")
    void shouldThrowWhenNoInventoriesFoundForStore() {
        ReserveStockCmd cmd = buildCmd(10L);

        when(inventoryRepository.getByStoreIdAndSKUs(storeId, List.of(sku)))
                .thenReturn(Collections.emptyList());

        assertThrows(ValidationException.class, () -> reserveStockService.reserve(cmd));
        verify(stockReservationRepository, never()).createBatch(any());
    }

    @Test
    @DisplayName("throws ValidationException when inventory is missing for a specific SKU")
    void shouldThrowWhenInventoryMissingForSku() {
        SKU anotherSku = SKU.of("SKU-002");
        Inventory inventoryForOtherSku = Inventory.builder()
                .id(InventoryId.of(UUID.randomUUID()))
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(anotherSku)
                .quantity(100L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();

        ReserveStockCmd cmd = buildCmd(10L);

        when(inventoryRepository.getByStoreIdAndSKUs(storeId, List.of(sku)))
                .thenReturn(List.of(inventoryForOtherSku));

        assertThrows(ValidationException.class, () -> reserveStockService.reserve(cmd));
        verify(stockReservationRepository, never()).createBatch(any());
    }

    @Test
    @DisplayName("throws InsufficientStockException when atomic SQL guard returns 0 (concurrent race)")
    void shouldThrowWhenAtomicSqlGuardFails() {
        // domain passes (available=50 >= 30) but concurrent update causes SQL to return 0
        Inventory inventory = buildInventory(100L, 50L);
        ReserveStockCmd cmd = buildCmd(30L);

        when(inventoryRepository.getByStoreIdAndSKUs(storeId, List.of(sku)))
                .thenReturn(List.of(inventory));
        when(inventoryRepository.reserve(eq(inventoryId), anyLong())).thenReturn(0);

        assertThrows(InsufficientStockException.class, () -> reserveStockService.reserve(cmd));
        verify(stockReservationRepository, never()).createBatch(any());
    }

    @Test
    @DisplayName("creates one reservation per SKU line item")
    void shouldCreateOneReservationPerSkuLine() {
        SKU sku2 = SKU.of("SKU-002");
        InventoryId inventoryId2 = InventoryId.of(UUID.randomUUID());

        Inventory inventory1 = buildInventory(100L, 0L);
        Inventory inventory2 = Inventory.builder()
                .id(inventoryId2)
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(sku2)
                .quantity(50L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();

        ReserveStockCmd cmd = ReserveStockCmd.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .skus(List.of(
                        ReserveStockCmd.ReserveStockSKU.builder()
                                .sku(sku).orderItemId(OrderItemId.of(UUID.randomUUID()))
                                .productId(ProductId.of(UUID.randomUUID())).quantity(10L).build(),
                        ReserveStockCmd.ReserveStockSKU.builder()
                                .sku(sku2).orderItemId(OrderItemId.of(UUID.randomUUID()))
                                .productId(ProductId.of(UUID.randomUUID())).quantity(5L).build()
                ))
                .build();

        when(inventoryRepository.getByStoreIdAndSKUs(storeId, List.of(sku, sku2)))
                .thenReturn(List.of(inventory1, inventory2));
        when(inventoryRepository.reserve(any(), anyLong())).thenReturn(1);
        when(stockReservationRepository.createBatch(any())).thenReturn(List.of());

        reserveStockService.reserve(cmd);

        verify(inventoryRepository).reserve(inventoryId, 10L);
        verify(inventoryRepository).reserve(inventoryId2, 5L);
        verify(stockReservationRepository).createBatch(argThat(list -> list.size() == 2));
    }

    @Test
    @DisplayName("reserves inventories in ascending inventory id order, regardless of command line order")
    void shouldReserveInSortedInventoryOrder() {
        SKU sku2 = SKU.of("SKU-002");
        InventoryId highId = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        InventoryId lowId = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        Inventory highInventory = Inventory.builder()
                .id(highId).merchantId(merchantId).storeId(storeId).productId(ProductId.of(UUID.randomUUID()))
                .sku(sku).quantity(100L).reservedQuantity(0L).status(InventoryStatus.ACTIVE).build();
        Inventory lowInventory = Inventory.builder()
                .id(lowId).merchantId(merchantId).storeId(storeId).productId(ProductId.of(UUID.randomUUID()))
                .sku(sku2).quantity(100L).reservedQuantity(0L).status(InventoryStatus.ACTIVE).build();

        ReserveStockCmd cmd = ReserveStockCmd.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .skus(List.of(
                        ReserveStockCmd.ReserveStockSKU.builder()
                                .sku(sku).orderItemId(OrderItemId.of(UUID.randomUUID()))
                                .productId(ProductId.of(UUID.randomUUID())).quantity(10L).build(),
                        ReserveStockCmd.ReserveStockSKU.builder()
                                .sku(sku2).orderItemId(OrderItemId.of(UUID.randomUUID()))
                                .productId(ProductId.of(UUID.randomUUID())).quantity(5L).build()
                ))
                .build();

        when(inventoryRepository.getByStoreIdAndSKUs(storeId, List.of(sku, sku2)))
                .thenReturn(List.of(highInventory, lowInventory));
        when(inventoryRepository.reserve(any(), anyLong())).thenReturn(1);
        when(stockReservationRepository.createBatch(any())).thenReturn(List.of());

        reserveStockService.reserve(cmd);

        InOrder inOrder = inOrder(inventoryRepository);
        inOrder.verify(inventoryRepository).reserve(lowId, 5L);
        inOrder.verify(inventoryRepository).reserve(highId, 10L);
    }
}
