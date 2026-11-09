package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult.Reason;
import com.dotran.oms.inventory.application.dto.reservation.ReservationLineStatus;
import com.dotran.oms.inventory.application.event.StockReplyFactory;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmStockReservationService")
class ConfirmStockReservationServiceTest {

    private static final InventoryId LOW = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final InventoryId HIGH = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Mock
    private StockReservationOrderRepository headerRepository;
    @Mock
    private StockReservationRepository stockReservationRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private TransactionTimeoutRepository transactionTimeoutRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private StockReplyFactory replyFactory;

    private ConfirmStockReservationService service;

    private final MerchantId merchantId = MerchantId.of(UUID.randomUUID());
    private final StoreId storeId = StoreId.of(UUID.randomUUID());
    private final OrderId orderId = OrderId.of(UUID.randomUUID());
    private final OrderItemId item1 = OrderItemId.of(UUID.randomUUID());
    private final OrderItemId item2 = OrderItemId.of(UUID.randomUUID());
    private final OrderItemId item3 = OrderItemId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = new ConfirmStockReservationService(headerRepository, stockReservationRepository,
                inventoryRepository, stockMovementRepository, transactionTimeoutRepository,
                new ReservationProperties(), outboxEventRepository, replyFactory);
    }

    private ConfirmStockReservationCmd cmd() {
        return ConfirmStockReservationCmd.builder()
                .eventId(EventId.newEventId())
                .occurredAt(Instant.parse("2026-11-07T10:00:00Z"))
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .build();
    }

    private StockReservationOrder header(OrderReservationStatus status) {
        ReservationRejection rejection = status == OrderReservationStatus.REJECTED
                ? ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND) : null;
        return new StockReservationOrder(orderId, merchantId, storeId, status, null, rejection);
    }

    private void existingHeader(OrderReservationStatus status) {
        when(headerRepository.insertIfAbsent(any())).thenReturn(false);
        when(headerRepository.lockForUpdate(orderId, merchantId, storeId)).thenReturn(Optional.of(header(status)));
    }

    private ReservationLineView view(long id, OrderItemId item, InventoryId inventoryId, long quantity,
                                     ReservationStatus status) {
        return new ReservationLineView(StockReservationId.of(id), item, inventoryId, ProductId.of(UUID.randomUUID()),
                SKU.of("SKU-" + id), quantity, status, Instant.parse("2026-11-07T10:15:00Z"));
    }

    private void lines(ReservationLineView... views) {
        when(stockReservationRepository.getLineViews(merchantId, storeId, orderId)).thenReturn(List.of(views));
    }

    @Test
    @DisplayName("confirms every line: reservations, inventories summed per row in UUID order, SALE movements, header CONFIRMED")
    void confirmsAllLines() {
        existingHeader(OrderReservationStatus.RESERVED);
        lines(view(1, item1, HIGH, 2, ReservationStatus.RESERVED),
                view(2, item2, LOW, 3, ReservationStatus.RESERVED),
                view(3, item3, HIGH, 4, ReservationStatus.RESERVED));
        List<StockReservationId> ids = List.of(StockReservationId.of(1L), StockReservationId.of(2L),
                StockReservationId.of(3L));
        when(stockReservationRepository.markConfirmed(ids)).thenReturn(Set.copyOf(ids));
        when(inventoryRepository.confirm(any(), anyLong())).thenReturn(1);
        when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(3);
        when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

        ConfirmStockReservationResult result = service.confirm(cmd());

        InOrder inOrder = inOrder(transactionTimeoutRepository, headerRepository, stockReservationRepository,
                inventoryRepository, stockMovementRepository);
        inOrder.verify(transactionTimeoutRepository).applyLocal(Duration.ofSeconds(3), Duration.ofSeconds(5));
        inOrder.verify(headerRepository).insertIfAbsent(header(OrderReservationStatus.CANCELLED));
        inOrder.verify(headerRepository).lockForUpdate(orderId, merchantId, storeId);
        inOrder.verify(stockReservationRepository).markConfirmed(ids);
        inOrder.verify(inventoryRepository).confirm(LOW, 3L);
        inOrder.verify(inventoryRepository).confirm(HIGH, 6L);
        inOrder.verify(stockMovementRepository).insertForReservations(ids, StockMovementType.SALE,
                ReservationStatus.CONFIRMED, null);
        ArgumentCaptor<StockReservationOrder> captor = ArgumentCaptor.forClass(StockReservationOrder.class);
        inOrder.verify(headerRepository).update(captor.capture(), eq(OrderReservationStatus.RESERVED));
        assertThat(captor.getValue().status()).isEqualTo(OrderReservationStatus.CONFIRMED);

        assertThat(result).isEqualTo(new ConfirmStockReservationResult.Confirmed(List.of(
                new ReservationLineStatus(item1, ReservationStatus.CONFIRMED),
                new ReservationLineStatus(item2, ReservationStatus.CONFIRMED),
                new ReservationLineStatus(item3, ReservationStatus.CONFIRMED))));

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("no header: creates a tombstone and answers UNKNOWN_ORDER, no stock touched")
    void unknownOrderCreatesTombstone() {
        when(headerRepository.insertIfAbsent(any())).thenReturn(true);
        when(headerRepository.lockForUpdate(orderId, merchantId, storeId))
                .thenReturn(Optional.of(header(OrderReservationStatus.CANCELLED)));

        ConfirmStockReservationResult result = service.confirm(cmd());

        verify(headerRepository).insertIfAbsent(header(OrderReservationStatus.CANCELLED));
        assertThat(result).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(Reason.UNKNOWN_ORDER, List.of()));
        verifyNoInteractions(stockReservationRepository, inventoryRepository, stockMovementRepository);
        verify(headerRepository, never()).update(any(), any());

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("header owned by another merchant or store: contract error")
    void ownershipMismatch() {
        when(headerRepository.insertIfAbsent(any())).thenReturn(false);
        when(headerRepository.lockForUpdate(orderId, merchantId, storeId)).thenReturn(Optional.empty());

        assertThrows(StockCommandOwnershipMismatchException.class, () -> service.confirm(cmd()));
        verifyNoInteractions(stockReservationRepository, inventoryRepository, stockMovementRepository);

        verify(outboxEventRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("missing orderId: contract error before any write")
    void missingOrderId() {
        ConfirmStockReservationCmd cmd = ConfirmStockReservationCmd.builder()
                .eventId(EventId.newEventId()).occurredAt(Instant.now()).merchantId(merchantId).storeId(storeId)
                .build();

        assertThrows(InvalidStockCommandException.class, () -> service.confirm(cmd));
        verifyNoInteractions(headerRepository, transactionTimeoutRepository);

        verify(outboxEventRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("duplicate after a confirm (header CONFIRMED): Confirmed with the current statuses, nothing written")
    void duplicateConfirm() {
        existingHeader(OrderReservationStatus.CONFIRMED);
        lines(view(1, item1, LOW, 2, ReservationStatus.CONFIRMED));

        ConfirmStockReservationResult result = service.confirm(cmd());

        assertThat(result).isEqualTo(new ConfirmStockReservationResult.Confirmed(List.of(
                new ReservationLineStatus(item1, ReservationStatus.CONFIRMED))));
        verify(stockReservationRepository, never()).markConfirmed(anyCollection());
        verifyNoInteractions(inventoryRepository, stockMovementRepository);
        verify(headerRepository, never()).update(any(), any());

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("header CANCELLED (released, or tombstone): ORDER_CLOSED")
    void closedByRelease() {
        existingHeader(OrderReservationStatus.CANCELLED);
        lines(view(1, item1, LOW, 2, ReservationStatus.RELEASED));

        ConfirmStockReservationResult result = service.confirm(cmd());

        assertThat(result).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(Reason.ORDER_CLOSED,
                List.of(new ReservationLineStatus(item1, ReservationStatus.RELEASED))));
        verifyNoInteractions(inventoryRepository, stockMovementRepository);

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("header REJECTED: ORDER_CLOSED without lines")
    void closedByRejection() {
        existingHeader(OrderReservationStatus.REJECTED);
        lines();

        ConfirmStockReservationResult result = service.confirm(cmd());

        assertThat(result).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(Reason.ORDER_CLOSED, List.of()));

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("one line EXPIRED: NOT_RESERVED for the whole order, nothing written")
    void oneLineExpired() {
        existingHeader(OrderReservationStatus.RESERVED);
        lines(view(1, item1, LOW, 2, ReservationStatus.RESERVED), view(2, item2, HIGH, 1, ReservationStatus.EXPIRED));

        ConfirmStockReservationResult result = service.confirm(cmd());

        assertThat(result).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(Reason.NOT_RESERVED, List.of(
                new ReservationLineStatus(item1, ReservationStatus.RESERVED),
                new ReservationLineStatus(item2, ReservationStatus.EXPIRED))));
        verify(stockReservationRepository, never()).markConfirmed(anyCollection());
        verifyNoInteractions(inventoryRepository, stockMovementRepository);
        verify(headerRepository, never()).update(any(), any());

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("header RESERVED but no lines: NOT_RESERVED")
    void reservedHeaderWithoutLines() {
        existingHeader(OrderReservationStatus.RESERVED);
        lines();

        ConfirmStockReservationResult result = service.confirm(cmd());

        assertThat(result).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(Reason.NOT_RESERVED, List.of()));

        verify(outboxEventRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("a line expired concurrently (guarded update lost): throws before touching inventory")
    void lostAgainstExpiry() {
        existingHeader(OrderReservationStatus.RESERVED);
        lines(view(1, item1, LOW, 2, ReservationStatus.RESERVED), view(2, item2, HIGH, 1, ReservationStatus.RESERVED));
        when(stockReservationRepository.markConfirmed(anyList())).thenReturn(Set.of(StockReservationId.of(1L)));

        assertThrows(InvalidReservationStateException.class, () -> service.confirm(cmd()));
        verifyNoInteractions(inventoryRepository, stockMovementRepository);
        verify(headerRepository, never()).update(any(), any());

        verify(outboxEventRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("inventory guarded confirm returns 0: throws, no movement")
    void inventoryConfirmFails() {
        existingHeader(OrderReservationStatus.RESERVED);
        lines(view(1, item1, LOW, 2, ReservationStatus.RESERVED));
        when(stockReservationRepository.markConfirmed(anyList())).thenReturn(Set.of(StockReservationId.of(1L)));
        when(inventoryRepository.confirm(LOW, 2L)).thenReturn(0);

        assertThrows(InvalidReservationException.class, () -> service.confirm(cmd()));
        verifyNoInteractions(stockMovementRepository);

        verify(outboxEventRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("fewer SALE movements than lines: throws")
    void movementShortfall() {
        existingHeader(OrderReservationStatus.RESERVED);
        lines(view(1, item1, LOW, 2, ReservationStatus.RESERVED));
        when(stockReservationRepository.markConfirmed(anyList())).thenReturn(Set.of(StockReservationId.of(1L)));
        when(inventoryRepository.confirm(LOW, 2L)).thenReturn(1);
        when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.confirm(cmd()));
        verify(headerRepository, never()).update(any(), any());

        verify(outboxEventRepository, never()).saveAll(anyList());
    }
}
