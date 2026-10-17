package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpireByInventoryTransaction")
class ExpireByInventoryTransactionTest {

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private ExpireByInventoryTransaction expireByInventoryTransaction;

    private TenantId tenantId;
    private StoreId storeId;
    private InventoryId inventoryId;

    @BeforeEach
    void setUp() {
        tenantId = TenantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
    }

    private StockReservation buildReservation(long quantity) {
        return StockReservation.builder()
                .id(new StockReservationId(1L))
                .tenantId(tenantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .orderId(OrderId.of(UUID.randomUUID()))
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().minusSeconds(60))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Inventory buildInventory(long quantity, long reservedQuantity) {
        return Inventory.builder()
                .id(inventoryId)
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(SKU.of("SKU-001"))
                .quantity(quantity)
                .reservedQuantity(reservedQuantity)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("returns early when reservation list is empty")
    void shouldReturnEarlyWhenNoReservations() {
        expireByInventoryTransaction.execute(inventoryId, Collections.emptyList());

        verifyNoInteractions(stockReservationRepository, inventoryRepository);
    }

    @Test
    @DisplayName("expires reservations and releases inventory atomically")
    void shouldExpireReservationsAndReleaseInventory() {
        StockReservation reservation = buildReservation(20L);
        Inventory inventory = buildInventory(100L, 20L);

        when(stockReservationRepository.expire(anyList())).thenReturn(List.of(reservation));
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.release(inventoryId, 20L)).thenReturn(1);

        expireByInventoryTransaction.execute(inventoryId, List.of(reservation));

        verify(stockReservationRepository).expire(anyList());
        verify(inventoryRepository).release(inventoryId, 20L);
    }

    @Test
    @DisplayName("sums quantities of multiple reservations before releasing")
    void shouldSumQuantitiesBeforeReleasing() {
        StockReservation r1 = buildReservation(10L);
        StockReservation r2 = buildReservation(15L);
        Inventory inventory = buildInventory(100L, 25L);

        when(stockReservationRepository.expire(anyList())).thenReturn(List.of(r1, r2));
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.release(inventoryId, 25L)).thenReturn(1);

        expireByInventoryTransaction.execute(inventoryId, List.of(r1, r2));

        verify(inventoryRepository).release(inventoryId, 25L);
    }

    @Test
    @DisplayName("throws NotFoundException when inventory not found")
    void shouldThrowWhenInventoryNotFound() {
        StockReservation reservation = buildReservation(20L);

        when(stockReservationRepository.expire(anyList())).thenReturn(List.of(reservation));
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> expireByInventoryTransaction.execute(inventoryId, List.of(reservation)));
        verify(inventoryRepository, never()).release(any(), anyLong());
    }

    @Test
    @DisplayName("throws InvalidReservationException when atomic release SQL returns 0 (concurrent race)")
    void shouldThrowWhenAtomicReleaseFails() {
        StockReservation reservation = buildReservation(20L);
        // domain passes (reservedQty=30 >= releaseQty=20) but concurrent modification causes SQL to return 0
        Inventory inventory = buildInventory(100L, 30L);

        when(stockReservationRepository.expire(anyList())).thenReturn(List.of(reservation));
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.release(inventoryId, 20L)).thenReturn(0);

        assertThrows(InvalidReservationException.class,
                () -> expireByInventoryTransaction.execute(inventoryId, List.of(reservation)));

        verify(inventoryRepository).release(inventoryId, 20L);
    }

    @Test
    @DisplayName("validates domain rule before atomic SQL — throws before DB call when invalid")
    void shouldValidateDomainBeforeAtomicSql() {
        StockReservation reservation = buildReservation(50L);
        Inventory inventory = buildInventory(100L, 10L); // reservedQty=10 < releaseQty=50

        when(stockReservationRepository.expire(anyList())).thenReturn(List.of(reservation));
        when(inventoryRepository.getById(inventoryId)).thenReturn(Optional.of(inventory));

        assertThrows(InvalidReservationException.class,
                () -> expireByInventoryTransaction.execute(inventoryId, List.of(reservation)));

        verify(inventoryRepository, never()).release(any(), anyLong());
    }

    @Test
    @DisplayName("throws InvalidReservationStateException when a reservation is not in RESERVED state")
    void shouldThrowWhenReservationInWrongState() {
        StockReservation alreadyExpired = StockReservation.builder()
                .id(new StockReservationId(1L))
                .tenantId(tenantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .orderId(OrderId.of(UUID.randomUUID()))
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(20L)
                .status(ReservationStatus.EXPIRED) // wrong state
                .expiresAt(Instant.now().minusSeconds(60))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        assertThrows(InvalidReservationStateException.class,
                () -> expireByInventoryTransaction.execute(inventoryId, List.of(alreadyExpired)));

        verifyNoInteractions(stockReservationRepository, inventoryRepository);
    }
}
