package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmStockReservationService")
class ConfirmStockReservationServiceTest {

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private ConfirmStockReservationService confirmStockReservationService;

    private MerchantId merchantId;
    private StoreId storeId;
    private OrderId orderId;
    private InventoryId inventoryId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        orderId = OrderId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
    }

    private StockReservation buildReservation(long id, InventoryId inventoryId, long quantity, ReservationStatus status) {
        return StockReservation.builder()
                .id(new StockReservationId(id))
                .merchantId(merchantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .orderId(orderId)
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(quantity)
                .status(status)
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private StockReservation buildReservation(long id, long quantity) {
        return buildReservation(id, inventoryId, quantity, ReservationStatus.RESERVED);
    }

    private ConfirmStockReservationCmd buildCmd() {
        return ConfirmStockReservationCmd.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .build();
    }

    private static Set<StockReservationId> idsOf(StockReservation... reservations) {
        return Set.of(Arrays.stream(reservations).map(StockReservation::getId).toArray(StockReservationId[]::new));
    }

    @Test
    @DisplayName("marks reservations CONFIRMED, then confirms inventory")
    void shouldConfirmReservationsAndUpdateInventory() {
        StockReservation reservation = buildReservation(1L, 30L);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(reservation));
        when(stockReservationRepository.markConfirmed(List.of(reservation.getId()))).thenReturn(idsOf(reservation));
        when(inventoryRepository.confirm(inventoryId, 30L)).thenReturn(1);

        confirmStockReservationService.confirm(buildCmd());

        InOrder inOrder = inOrder(stockReservationRepository, inventoryRepository);
        inOrder.verify(stockReservationRepository).markConfirmed(List.of(reservation.getId()));
        inOrder.verify(inventoryRepository).confirm(inventoryId, 30L);
    }

    @Test
    @DisplayName("groups reservations by inventoryId, sums quantities and confirms inventories in ascending id order")
    void shouldGroupByInventoryAndConfirmInSortedOrder() {
        InventoryId lowId = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        InventoryId highId = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        StockReservation r1 = buildReservation(1L, highId, 10L, ReservationStatus.RESERVED);
        StockReservation r2 = buildReservation(2L, highId, 20L, ReservationStatus.RESERVED);
        StockReservation r3 = buildReservation(3L, lowId, 15L, ReservationStatus.RESERVED);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(r1, r2, r3));
        when(stockReservationRepository.markConfirmed(any())).thenReturn(idsOf(r1, r2, r3));
        when(inventoryRepository.confirm(highId, 30L)).thenReturn(1);
        when(inventoryRepository.confirm(lowId, 15L)).thenReturn(1);

        confirmStockReservationService.confirm(buildCmd());

        InOrder inOrder = inOrder(inventoryRepository);
        inOrder.verify(inventoryRepository).confirm(lowId, 15L);
        inOrder.verify(inventoryRepository).confirm(highId, 30L);
    }

    @Test
    @DisplayName("is a no-op when every reservation of the order is already CONFIRMED (redelivery)")
    void shouldBeNoOpWhenAlreadyConfirmed() {
        StockReservation r1 = buildReservation(1L, inventoryId, 10L, ReservationStatus.CONFIRMED);
        StockReservation r2 = buildReservation(2L, inventoryId, 20L, ReservationStatus.CONFIRMED);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(r1, r2));

        confirmStockReservationService.confirm(buildCmd());

        verify(stockReservationRepository, never()).markConfirmed(any());
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("rejects the whole order when one reservation already EXPIRED, without writing anything")
    void shouldRejectWhenOneReservationExpired() {
        StockReservation reserved = buildReservation(1L, 10L);
        StockReservation expired = buildReservation(2L, inventoryId, 20L, ReservationStatus.EXPIRED);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(reserved, expired));

        assertThrows(InvalidReservationStateException.class,
                () -> confirmStockReservationService.confirm(buildCmd()));

        verify(stockReservationRepository, never()).markConfirmed(any());
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("rejects the whole order when one reservation was RELEASED, with a final 'not RESERVED' error")
    void shouldRejectWhenOneReservationReleased() {
        StockReservation reserved = buildReservation(1L, 10L);
        StockReservation released = buildReservation(2L, inventoryId, 20L, ReservationStatus.RELEASED);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(reserved, released));

        InvalidReservationStateException ex = assertThrows(InvalidReservationStateException.class,
                () -> confirmStockReservationService.confirm(buildCmd()));

        assertTrue(ex.getMessage().contains("is not RESERVED"));
        verify(stockReservationRepository, never()).markConfirmed(any());
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("rejects an order mixing CONFIRMED and RESERVED reservations, without writing anything")
    void shouldRejectMixOfConfirmedAndReserved() {
        StockReservation confirmed = buildReservation(1L, inventoryId, 10L, ReservationStatus.CONFIRMED);
        StockReservation reserved = buildReservation(2L, 20L);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(confirmed, reserved));

        assertThrows(InvalidReservationStateException.class,
                () -> confirmStockReservationService.confirm(buildCmd()));

        verify(stockReservationRepository, never()).markConfirmed(any());
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("throws and does not touch inventory when a reservation was changed concurrently (guarded update lost)")
    void shouldThrowWhenGuardedTransitionLost() {
        StockReservation r1 = buildReservation(1L, 10L);
        StockReservation r2 = buildReservation(2L, 20L);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(r1, r2));
        when(stockReservationRepository.markConfirmed(any())).thenReturn(idsOf(r1));

        InvalidReservationStateException ex = assertThrows(InvalidReservationStateException.class,
                () -> confirmStockReservationService.confirm(buildCmd()));

        assertTrue(ex.getMessage().contains("modified concurrently"));
        verify(inventoryRepository, never()).confirm(any(), anyLong());
    }

    @Test
    @DisplayName("throws ValidationException when no reservations found for order")
    void shouldThrowWhenNoReservationsFound() {
        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(Collections.emptyList());

        assertThrows(ValidationException.class,
                () -> confirmStockReservationService.confirm(buildCmd()));
        verify(stockReservationRepository, never()).markConfirmed(any());
        verify(inventoryRepository, never()).confirm(any(), anyLong());
    }

    @Test
    @DisplayName("throws InvalidReservationException when inventory atomic update returns 0")
    void shouldThrowWhenInventoryConfirmFails() {
        StockReservation reservation = buildReservation(1L, 30L);

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(reservation));
        when(stockReservationRepository.markConfirmed(any())).thenReturn(idsOf(reservation));
        when(inventoryRepository.confirm(inventoryId, 30L)).thenReturn(0);

        assertThrows(InvalidReservationException.class,
                () -> confirmStockReservationService.confirm(buildCmd()));
    }
}
