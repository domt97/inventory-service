package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
import com.dotran.oms.core.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpireStockReservationService")
class ExpireStockReservationServiceTest {

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private ExpireByInventoryTransaction expireByInventoryTransaction;

    @InjectMocks
    private ExpireStockReservationService expireStockReservationService;

    private StockReservation buildReservation(InventoryId inventoryId, long quantity) {
        return StockReservation.builder()
                .id(new StockReservationId(1L))
                .tenantId(TenantId.of(UUID.randomUUID()))
                .storeId(StoreId.of(UUID.randomUUID()))
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

    @Test
    @DisplayName("returns early when no expired reservations found")
    void shouldReturnEarlyWhenNoExpiredReservations() {
        when(stockReservationRepository.getExpiredReservations(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        expireStockReservationService.execute();

        verifyNoInteractions(expireByInventoryTransaction);
    }

    @Test
    @DisplayName("groups expired reservations by inventoryId and delegates per inventory")
    void shouldGroupByInventoryAndDelegatePerInventory() {
        InventoryId inventoryId1 = InventoryId.of(UUID.randomUUID());
        InventoryId inventoryId2 = InventoryId.of(UUID.randomUUID());

        StockReservation r1 = buildReservation(inventoryId1, 10L);
        StockReservation r2 = buildReservation(inventoryId1, 20L);
        StockReservation r3 = buildReservation(inventoryId2, 15L);

        when(stockReservationRepository.getExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(r1, r2, r3));

        expireStockReservationService.execute();

        verify(expireByInventoryTransaction).execute(eq(inventoryId1), argThat(list -> list.size() == 2));
        verify(expireByInventoryTransaction).execute(eq(inventoryId2), argThat(list -> list.size() == 1));
    }

    @Test
    @DisplayName("continues processing other inventories when one fails with InvalidReservationException")
    void shouldContinueWhenOneInventoryFailsWithInvalidReservation() {
        assertContinuesAfterFailure(new InvalidReservationException());
    }

    @Test
    @DisplayName("continues processing other inventories when one fails with NotFoundException")
    void shouldContinueWhenOneInventoryFailsWithNotFound() {
        assertContinuesAfterFailure(new NotFoundException("Inventory not found"));
    }

    private void assertContinuesAfterFailure(RuntimeException failure) {
        InventoryId inventoryId1 = InventoryId.of(UUID.randomUUID());
        InventoryId inventoryId2 = InventoryId.of(UUID.randomUUID());

        StockReservation r1 = buildReservation(inventoryId1, 10L);
        StockReservation r2 = buildReservation(inventoryId2, 20L);

        when(stockReservationRepository.getExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(r1, r2));
        doAnswer(invocation -> {
            InventoryId id = invocation.getArgument(0);
            if (id.equals(inventoryId1)) {
                throw failure;
            }
            return null;
        }).when(expireByInventoryTransaction).execute(any(InventoryId.class), any());

        assertDoesNotThrow(() -> expireStockReservationService.execute());

        verify(expireByInventoryTransaction).execute(eq(inventoryId1), any());
        verify(expireByInventoryTransaction).execute(eq(inventoryId2), any());
    }
}
