package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private TenantId tenantId;
    private StoreId storeId;
    private OrderId orderId;
    private InventoryId inventoryId;

    @BeforeEach
    void setUp() {
        tenantId = TenantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        orderId = OrderId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
    }

    private StockReservation buildReservation(long quantity) {
        return StockReservation.builder()
                .id(new StockReservationId(1L))
                .tenantId(tenantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .orderId(orderId)
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private ConfirmStockReservationCmd buildCmd() {
        return ConfirmStockReservationCmd.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .orderId(orderId)
                .build();
    }

    @Test
    @DisplayName("confirms reservations and updates inventory atomically")
    void shouldConfirmReservationsAndUpdateInventory() {
        StockReservation reservation = buildReservation(30L);
        ConfirmStockReservationCmd cmd = buildCmd();

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(reservation));
        when(inventoryRepository.confirm(inventoryId, 30L)).thenReturn(1);

        confirmStockReservationService.confirm(cmd);

        verify(stockReservationRepository).confirm(any());
        verify(inventoryRepository).confirm(inventoryId, 30L);
    }

    @Test
    @DisplayName("groups reservations by inventoryId and sums quantities")
    void shouldGroupByInventoryAndSumQuantities() {
        InventoryId inventoryId2 = InventoryId.of(UUID.randomUUID());

        StockReservation r1 = buildReservation(10L);
        StockReservation r2 = buildReservation(20L);
        StockReservation r3 = StockReservation.builder()
                .id(new StockReservationId(2L))
                .tenantId(tenantId)
                .storeId(storeId)
                .inventoryId(inventoryId2)
                .orderId(orderId)
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(15L)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ConfirmStockReservationCmd cmd = buildCmd();

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(r1, r2, r3));
        when(inventoryRepository.confirm(inventoryId, 30L)).thenReturn(1);
        when(inventoryRepository.confirm(inventoryId2, 15L)).thenReturn(1);

        confirmStockReservationService.confirm(cmd);

        verify(inventoryRepository).confirm(inventoryId, 30L);
        verify(inventoryRepository).confirm(inventoryId2, 15L);
    }

    @Test
    @DisplayName("throws ValidationException when no reservations found for order")
    void shouldThrowWhenNoReservationsFound() {
        ConfirmStockReservationCmd cmd = buildCmd();

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(Collections.emptyList());

        assertThrows(ValidationException.class,
                () -> confirmStockReservationService.confirm(cmd));
        verify(inventoryRepository, never()).confirm(any(), anyLong());
    }

    @Test
    @DisplayName("throws InvalidReservationException when inventory atomic update returns 0")
    void shouldThrowWhenInventoryConfirmFails() {
        StockReservation reservation = buildReservation(30L);
        ConfirmStockReservationCmd cmd = buildCmd();

        when(stockReservationRepository.getByOrderId(orderId)).thenReturn(List.of(reservation));
        when(inventoryRepository.confirm(inventoryId, 30L)).thenReturn(0);

        assertThrows(InvalidReservationException.class,
                () -> confirmStockReservationService.confirm(cmd));
    }
}
