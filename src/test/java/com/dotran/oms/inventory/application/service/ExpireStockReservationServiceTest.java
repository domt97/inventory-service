package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.config.ReservationExpiryProperties;
import com.dotran.oms.inventory.application.repository.ExpiryCursor;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpireStockReservationService")
class ExpireStockReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final int BATCH_SIZE = 2;

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private ExpireByInventoryTransaction expireByInventoryTransaction;

    @Mock
    private Clock clock;

    private ExpireStockReservationService expireStockReservationService;

    @BeforeEach
    void setUp() {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setBatchSize(BATCH_SIZE);
        properties.setTimeBudget(Duration.ofSeconds(40));
        expireStockReservationService = new ExpireStockReservationService(
                stockReservationRepository, expireByInventoryTransaction, properties, clock);
        lenient().when(clock.instant()).thenReturn(NOW);
    }

    private StockReservation buildReservation(long id, InventoryId inventoryId, Instant expiresAt) {
        return StockReservation.builder()
                .id(new StockReservationId(id))
                .tenantId(TenantId.of(UUID.randomUUID()))
                .storeId(StoreId.of(UUID.randomUUID()))
                .inventoryId(inventoryId)
                .orderId(OrderId.of(UUID.randomUUID()))
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(10L)
                .status(ReservationStatus.RESERVED)
                .expiresAt(expiresAt)
                .createdAt(expiresAt.minus(Duration.ofMinutes(30)))
                .updatedAt(expiresAt.minus(Duration.ofMinutes(30)))
                .build();
    }

    private StockReservation buildReservation(long id, InventoryId inventoryId) {
        return buildReservation(id, inventoryId, NOW.minusSeconds(600 - id));
    }

    @Test
    @DisplayName("queries once and expires nothing when no expired reservations found")
    void shouldReturnEarlyWhenNoExpiredReservations() {
        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(Collections.emptyList());

        expireStockReservationService.execute();

        verify(stockReservationRepository, times(1)).getExpiredReservations(any(), any(), anyInt());
        verifyNoInteractions(expireByInventoryTransaction);
    }

    @Test
    @DisplayName("groups a page by inventoryId and delegates per inventory")
    void shouldGroupByInventoryAndDelegatePerInventory() {
        ExpireStockReservationService service = serviceWithBatchSize(10);
        InventoryId inventoryId1 = InventoryId.of(UUID.randomUUID());
        InventoryId inventoryId2 = InventoryId.of(UUID.randomUUID());

        StockReservation r1 = buildReservation(1L, inventoryId1);
        StockReservation r2 = buildReservation(2L, inventoryId1);
        StockReservation r3 = buildReservation(3L, inventoryId2);

        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), 10))
                .thenReturn(List.of(r1, r2, r3));

        service.execute();

        verify(expireByInventoryTransaction).execute(eq(inventoryId1), argThat(list -> list.size() == 2));
        verify(expireByInventoryTransaction).execute(eq(inventoryId2), argThat(list -> list.size() == 1));
    }

    @Test
    @DisplayName("does not query again after a partial page")
    void shouldStopAfterPartialPage() {
        InventoryId inventoryId = InventoryId.of(UUID.randomUUID());
        StockReservation r1 = buildReservation(1L, inventoryId);

        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(List.of(r1));

        expireStockReservationService.execute();

        verify(stockReservationRepository, times(1)).getExpiredReservations(any(), any(), anyInt());
        verify(expireByInventoryTransaction).execute(eq(inventoryId), argThat(list -> list.size() == 1));
    }

    @Test
    @DisplayName("after a full page queries the next page from the last row of the previous one, until empty")
    void shouldPageWithCursorOfLastRowUntilEmpty() {
        InventoryId inventoryId = InventoryId.of(UUID.randomUUID());
        StockReservation r1 = buildReservation(7L, inventoryId, NOW.minusSeconds(300));
        StockReservation r2 = buildReservation(4L, inventoryId, NOW.minusSeconds(200));
        ExpiryCursor afterPage1 = new ExpiryCursor(NOW.minusSeconds(200), new StockReservationId(4L));

        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(List.of(r1, r2));
        when(stockReservationRepository.getExpiredReservations(NOW, afterPage1, BATCH_SIZE))
                .thenReturn(List.of());

        expireStockReservationService.execute();

        verify(stockReservationRepository).getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE);
        verify(stockReservationRepository).getExpiredReservations(NOW, afterPage1, BATCH_SIZE);
        verify(stockReservationRepository, times(2)).getExpiredReservations(any(), any(), anyInt());
    }

    @Test
    @DisplayName("stops after a partial page following a full one, using the run start time for every page")
    void shouldStopAfterPartialSecondPage() {
        InventoryId inventoryId = InventoryId.of(UUID.randomUUID());
        StockReservation r1 = buildReservation(1L, inventoryId);
        StockReservation r2 = buildReservation(2L, inventoryId);
        StockReservation r3 = buildReservation(3L, inventoryId);

        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(3));
        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(List.of(r1, r2));
        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.after(r2), BATCH_SIZE))
                .thenReturn(List.of(r3));

        expireStockReservationService.execute();

        verify(stockReservationRepository, times(2)).getExpiredReservations(eq(NOW), any(), eq(BATCH_SIZE));
        verify(stockReservationRepository, never()).getExpiredReservations(any(), eq(ExpiryCursor.after(r3)), anyInt());
    }

    @Test
    @DisplayName("same inventory spread over two pages is expired once per page")
    void shouldDelegateOncePerPageForSameInventory() {
        InventoryId inventoryId = InventoryId.of(UUID.randomUUID());
        StockReservation r1 = buildReservation(1L, inventoryId);
        StockReservation r2 = buildReservation(2L, inventoryId);
        StockReservation r3 = buildReservation(3L, inventoryId);

        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(List.of(r1, r2));
        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.after(r2), BATCH_SIZE))
                .thenReturn(List.of(r3));

        expireStockReservationService.execute();

        verify(expireByInventoryTransaction).execute(eq(inventoryId), eq(List.of(r1, r2)));
        verify(expireByInventoryTransaction).execute(eq(inventoryId), eq(List.of(r3)));
    }

    @Test
    @DisplayName("stops starting new pages once the time budget is used up")
    void shouldStopWhenTimeBudgetReached() {
        InventoryId inventoryId = InventoryId.of(UUID.randomUUID());
        StockReservation r1 = buildReservation(1L, inventoryId);
        StockReservation r2 = buildReservation(2L, inventoryId);

        // start, check before page 1, check before page 2 (budget of 40 s exhausted), finish
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1), NOW.plusSeconds(40), NOW.plusSeconds(41));
        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(List.of(r1, r2));

        expireStockReservationService.execute();

        verify(stockReservationRepository, times(1)).getExpiredReservations(any(), any(), anyInt());
        verify(stockReservationRepository, never()).getExpiredReservations(any(), eq(ExpiryCursor.after(r2)), anyInt());
        verify(expireByInventoryTransaction, times(1)).execute(any(), any());
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

    @Test
    @DisplayName("moves the cursor past a failed inventory and still fetches the next page")
    void shouldFetchNextPageAfterFailedInventory() {
        InventoryId failing = InventoryId.of(UUID.randomUUID());
        InventoryId healthy = InventoryId.of(UUID.randomUUID());
        StockReservation r1 = buildReservation(1L, failing);
        StockReservation r2 = buildReservation(2L, failing);
        StockReservation r3 = buildReservation(3L, healthy);

        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), BATCH_SIZE))
                .thenReturn(List.of(r1, r2));
        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.after(r2), BATCH_SIZE))
                .thenReturn(List.of(r3));
        doThrow(new InvalidReservationException()).when(expireByInventoryTransaction).execute(eq(failing), any());

        assertDoesNotThrow(() -> expireStockReservationService.execute());

        verify(expireByInventoryTransaction).execute(eq(healthy), eq(List.of(r3)));
    }

    private void assertContinuesAfterFailure(RuntimeException failure) {
        ExpireStockReservationService service = serviceWithBatchSize(10);
        InventoryId inventoryId1 = InventoryId.of(UUID.randomUUID());
        InventoryId inventoryId2 = InventoryId.of(UUID.randomUUID());

        StockReservation r1 = buildReservation(1L, inventoryId1);
        StockReservation r2 = buildReservation(2L, inventoryId2);

        when(stockReservationRepository.getExpiredReservations(NOW, ExpiryCursor.start(), 10))
                .thenReturn(List.of(r1, r2));
        doAnswer(invocation -> {
            InventoryId id = invocation.getArgument(0);
            if (id.equals(inventoryId1)) {
                throw failure;
            }
            return null;
        }).when(expireByInventoryTransaction).execute(any(InventoryId.class), any());

        assertDoesNotThrow(service::execute);

        verify(expireByInventoryTransaction).execute(eq(inventoryId1), any());
        verify(expireByInventoryTransaction).execute(eq(inventoryId2), any());
    }

    private ExpireStockReservationService serviceWithBatchSize(int batchSize) {
        ReservationExpiryProperties properties = new ReservationExpiryProperties();
        properties.setBatchSize(batchSize);
        return new ExpireStockReservationService(
                stockReservationRepository, expireByInventoryTransaction, properties, clock);
    }
}
