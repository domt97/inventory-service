package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult.Line;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("ReleaseStockReservationService")
class ReleaseStockReservationServiceTest {

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

    private ReleaseStockReservationService service;

    private final MerchantId merchantId = MerchantId.of(UUID.randomUUID());
    private final StoreId storeId = StoreId.of(UUID.randomUUID());
    private final OrderId orderId = OrderId.of(UUID.randomUUID());
    private final OrderItemId item1 = OrderItemId.of(UUID.randomUUID());
    private final OrderItemId item2 = OrderItemId.of(UUID.randomUUID());
    private final OrderItemId item3 = OrderItemId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = new ReleaseStockReservationService(headerRepository, stockReservationRepository,
                inventoryRepository, stockMovementRepository, transactionTimeoutRepository,
                new ReservationProperties());
    }

    private ReleaseStockReservationCmd cmd(Boolean restock) {
        return ReleaseStockReservationCmd.builder()
                .eventId(EventId.newEventId())
                .occurredAt(Instant.parse("2026-11-07T10:00:00Z"))
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .restock(restock)
                .reason("CUSTOMER_CANCELLED")
                .build();
    }

    private StockReservationOrder header(OrderReservationStatus status, Boolean restock) {
        return new StockReservationOrder(orderId, merchantId, storeId, status, restock, null);
    }

    private void existingHeader(OrderReservationStatus status, Boolean restock) {
        when(headerRepository.insertIfAbsent(any())).thenReturn(false);
        when(headerRepository.lockForUpdate(orderId, merchantId, storeId))
                .thenReturn(Optional.of(header(status, restock)));
    }

    private ReservationLineView view(long id, OrderItemId item, InventoryId inventoryId, long quantity,
                                     ReservationStatus status) {
        return new ReservationLineView(StockReservationId.of(id), item, inventoryId, ProductId.of(UUID.randomUUID()),
                SKU.of("SKU-" + id), quantity, status, Instant.parse("2026-11-07T10:15:00Z"));
    }

    private void lines(ReservationLineView... views) {
        when(stockReservationRepository.getLineViews(merchantId, storeId, orderId)).thenReturn(List.of(views));
    }

    private StockReservationOrder updatedHeader(OrderReservationStatus expected) {
        ArgumentCaptor<StockReservationOrder> captor = ArgumentCaptor.forClass(StockReservationOrder.class);
        verify(headerRepository).update(captor.capture(), eq(expected));
        return captor.getValue();
    }

    @Nested
    @DisplayName("open order (header RESERVED or CONFIRMED)")
    class OpenOrder {

        @Test
        @DisplayName("RESERVED lines: released per inventory in UUID order, RESERVE_RELEASE movements, header CANCELLED")
        void releasesHolds() {
            existingHeader(OrderReservationStatus.RESERVED, null);
            lines(view(1, item1, HIGH, 2, ReservationStatus.RESERVED),
                    view(2, item2, LOW, 3, ReservationStatus.RESERVED),
                    view(3, item3, HIGH, 1, ReservationStatus.RESERVED));
            List<StockReservationId> ids = List.of(StockReservationId.of(1L), StockReservationId.of(2L),
                    StockReservationId.of(3L));
            when(stockReservationRepository.markReleased(ids)).thenReturn(Set.copyOf(ids));
            when(inventoryRepository.release(any(), anyLong())).thenReturn(1);
            when(stockMovementRepository.insertForReservations(anyCollection(), any(), any(), any())).thenReturn(3);
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(false));

            InOrder inOrder = inOrder(transactionTimeoutRepository, headerRepository, stockReservationRepository,
                    inventoryRepository, stockMovementRepository);
            inOrder.verify(transactionTimeoutRepository).applyLocal(Duration.ofSeconds(3), Duration.ofSeconds(5));
            inOrder.verify(headerRepository).insertIfAbsent(header(OrderReservationStatus.CANCELLED, false));
            inOrder.verify(headerRepository).lockForUpdate(orderId, merchantId, storeId);
            inOrder.verify(stockReservationRepository).markReleased(ids);
            inOrder.verify(inventoryRepository).release(LOW, 3L);
            inOrder.verify(inventoryRepository).release(HIGH, 3L);
            inOrder.verify(stockMovementRepository).insertForReservations(Set.copyOf(ids),
                    StockMovementType.RESERVE_RELEASE, ReservationStatus.RELEASED, "CUSTOMER_CANCELLED");
            verify(stockReservationRepository, never()).markReturned(anyCollection());
            verify(inventoryRepository, never()).stockIn(any(), any(), any(), anyLong());

            StockReservationOrder closed = updatedHeader(OrderReservationStatus.RESERVED);
            assertThat(closed.status()).isEqualTo(OrderReservationStatus.CANCELLED);
            assertThat(closed.restock()).isFalse();
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(false, List.of(
                    new Line(item1, ReservationStatus.RELEASED, false),
                    new Line(item2, ReservationStatus.RELEASED, false),
                    new Line(item3, ReservationStatus.RELEASED, false))));
        }

        @Test
        @DisplayName("confirmed order with restock: RETURNED, stock back with RETURN movements (BR-ORD-09)")
        void restocksConfirmed() {
            existingHeader(OrderReservationStatus.CONFIRMED, null);
            lines(view(1, item1, LOW, 2, ReservationStatus.CONFIRMED));
            when(stockReservationRepository.markReturned(List.of(StockReservationId.of(1L))))
                    .thenReturn(Set.of(StockReservationId.of(1L)));
            when(inventoryRepository.stockIn(LOW, merchantId, storeId, 2L)).thenReturn(1);
            when(stockMovementRepository.insertForReservations(anyCollection(), any(), any(), any())).thenReturn(1);
            when(headerRepository.update(any(), eq(OrderReservationStatus.CONFIRMED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(true));

            verify(stockReservationRepository, never()).markReleased(anyCollection());
            verify(inventoryRepository, never()).release(any(), anyLong());
            verify(stockMovementRepository).insertForReservations(Set.of(StockReservationId.of(1L)),
                    StockMovementType.RETURN, ReservationStatus.RETURNED, "CUSTOMER_CANCELLED");
            StockReservationOrder closed = updatedHeader(OrderReservationStatus.CONFIRMED);
            assertThat(closed.status()).isEqualTo(OrderReservationStatus.CANCELLED);
            assertThat(closed.restock()).isTrue();
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(true, List.of(
                    new Line(item1, ReservationStatus.RETURNED, true))));
        }

        @Test
        @DisplayName("confirmed order without restock: goods stay sold, no stock change, header CANCELLED")
        void keepsConfirmedWithoutRestock() {
            existingHeader(OrderReservationStatus.CONFIRMED, null);
            lines(view(1, item1, LOW, 2, ReservationStatus.CONFIRMED));
            when(headerRepository.update(any(), eq(OrderReservationStatus.CONFIRMED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(false));

            verify(stockReservationRepository, never()).markReturned(anyCollection());
            verify(stockReservationRepository, never()).markReleased(anyCollection());
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
            assertThat(updatedHeader(OrderReservationStatus.CONFIRMED).restock()).isFalse();
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(false, List.of(
                    new Line(item1, ReservationStatus.CONFIRMED, false))));
        }

        @Test
        @DisplayName("a RESERVED line lost to a concurrent expiry is reported EXPIRED and not released twice")
        void lostAgainstExpiry() {
            existingHeader(OrderReservationStatus.RESERVED, null);
            lines(view(1, item1, LOW, 2, ReservationStatus.RESERVED), view(2, item2, HIGH, 5, ReservationStatus.RESERVED));
            when(stockReservationRepository.markReleased(anyList())).thenReturn(Set.of(StockReservationId.of(1L)));
            when(inventoryRepository.release(LOW, 2L)).thenReturn(1);
            when(stockMovementRepository.insertForReservations(anyCollection(), any(), any(), any())).thenReturn(1);
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(false));

            verify(inventoryRepository, never()).release(eq(HIGH), anyLong());
            assertThat(result.lines()).containsExactly(
                    new Line(item1, ReservationStatus.RELEASED, false),
                    new Line(item2, ReservationStatus.EXPIRED, false));
        }

        @Test
        @DisplayName("already EXPIRED lines are left alone")
        void expiredLinesUntouched() {
            existingHeader(OrderReservationStatus.RESERVED, null);
            lines(view(1, item1, LOW, 2, ReservationStatus.EXPIRED));
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(true));

            verify(stockReservationRepository, never()).markReleased(anyCollection());
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
            assertThat(result.lines()).containsExactly(new Line(item1, ReservationStatus.EXPIRED, false));
        }

        @Test
        @DisplayName("a CONFIRMED line changed concurrently during restock: throws, nothing committed")
        void returnLost() {
            existingHeader(OrderReservationStatus.CONFIRMED, null);
            lines(view(1, item1, LOW, 2, ReservationStatus.CONFIRMED));
            when(stockReservationRepository.markReturned(anyList())).thenReturn(Set.of());

            assertThrows(InvalidReservationStateException.class, () -> service.release(cmd(true)));
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
            verify(headerRepository, never()).update(any(), any());
        }

        @Test
        @DisplayName("stock-in of a return updates 0 rows: throws")
        void returnStockInFails() {
            existingHeader(OrderReservationStatus.CONFIRMED, null);
            lines(view(1, item1, LOW, 2, ReservationStatus.CONFIRMED));
            when(stockReservationRepository.markReturned(anyList())).thenReturn(Set.of(StockReservationId.of(1L)));
            when(inventoryRepository.stockIn(LOW, merchantId, storeId, 2L)).thenReturn(0);

            assertThrows(IllegalStateException.class, () -> service.release(cmd(true)));
            verifyNoInteractions(stockMovementRepository);
        }
    }

    @Nested
    @DisplayName("closed order and out-of-order commands")
    class Closed {

        @Test
        @DisplayName("no header: tombstone CANCELLED with the command's restock, nothing else")
        void releaseBeforeReserve() {
            when(headerRepository.insertIfAbsent(any())).thenReturn(true);
            when(headerRepository.lockForUpdate(orderId, merchantId, storeId))
                    .thenReturn(Optional.of(header(OrderReservationStatus.CANCELLED, true)));

            ReleaseStockReservationResult result = service.release(cmd(true));

            verify(headerRepository).insertIfAbsent(header(OrderReservationStatus.CANCELLED, true));
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(true, List.of()));
            verifyNoInteractions(stockReservationRepository, inventoryRepository, stockMovementRepository);
            verify(headerRepository, never()).update(any(), any());
        }

        @Test
        @DisplayName("first release on a tombstone left by a confirm (restock unset): stores restock")
        void firstReleaseOnConfirmTombstone() {
            existingHeader(OrderReservationStatus.CANCELLED, null);
            lines();
            when(headerRepository.update(any(), eq(OrderReservationStatus.CANCELLED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(true));

            StockReservationOrder updated = updatedHeader(OrderReservationStatus.CANCELLED);
            assertThat(updated.status()).isEqualTo(OrderReservationStatus.CANCELLED);
            assertThat(updated.restock()).isTrue();
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(true, List.of()));
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
        }

        @Test
        @DisplayName("release after a rejection: no lines, first release stores restock")
        void releaseAfterRejection() {
            existingHeader(OrderReservationStatus.REJECTED, null);
            lines();
            when(headerRepository.update(any(), eq(OrderReservationStatus.REJECTED))).thenReturn(1);

            ReleaseStockReservationResult result = service.release(cmd(false));

            assertThat(updatedHeader(OrderReservationStatus.REJECTED).status())
                    .isEqualTo(OrderReservationStatus.REJECTED);
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(false, List.of()));
        }

        @Test
        @DisplayName("duplicate release with another restock: answers the stored restock, changes nothing")
        void duplicateWithDifferentRestock() {
            existingHeader(OrderReservationStatus.CANCELLED, false);
            lines(view(1, item1, LOW, 2, ReservationStatus.RELEASED), view(2, item2, LOW, 1, ReservationStatus.CONFIRMED));

            ReleaseStockReservationResult result = service.release(cmd(true));

            verify(headerRepository, never()).update(any(), any());
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
            verify(stockReservationRepository, never()).markReleased(anyCollection());
            verify(stockReservationRepository, never()).markReturned(anyCollection());
            assertThat(result).isEqualTo(new ReleaseStockReservationResult(false, List.of(
                    new Line(item1, ReservationStatus.RELEASED, false),
                    new Line(item2, ReservationStatus.CONFIRMED, false))));
        }

        @Test
        @DisplayName("duplicate after a restock: RETURNED lines reported restocked")
        void duplicateAfterRestock() {
            existingHeader(OrderReservationStatus.CANCELLED, true);
            lines(view(1, item1, LOW, 2, ReservationStatus.RETURNED));

            ReleaseStockReservationResult result = service.release(cmd(true));

            assertThat(result).isEqualTo(new ReleaseStockReservationResult(true, List.of(
                    new Line(item1, ReservationStatus.RETURNED, true))));
        }
    }

    @Nested
    @DisplayName("contract errors")
    class ContractErrors {

        @Test
        @DisplayName("restock missing: nothing written")
        void restockRequired() {
            assertThrows(InvalidStockCommandException.class, () -> service.release(cmd(null)));
            verifyNoInteractions(headerRepository, transactionTimeoutRepository, stockReservationRepository);
        }

        @Test
        @DisplayName("reason longer than 50 characters: nothing written")
        void reasonTooLong() {
            ReleaseStockReservationCmd cmd = ReleaseStockReservationCmd.builder()
                    .eventId(EventId.newEventId()).occurredAt(Instant.now()).merchantId(merchantId).storeId(storeId)
                    .orderId(orderId).restock(false).reason("x".repeat(51)).build();

            assertThrows(InvalidStockCommandException.class, () -> service.release(cmd));
            verifyNoInteractions(headerRepository);
        }

        @Test
        @DisplayName("header owned by another merchant or store")
        void ownershipMismatch() {
            when(headerRepository.insertIfAbsent(any())).thenReturn(false);
            when(headerRepository.lockForUpdate(orderId, merchantId, storeId)).thenReturn(Optional.empty());

            assertThrows(StockCommandOwnershipMismatchException.class, () -> service.release(cmd(false)));
            verifyNoInteractions(stockReservationRepository, inventoryRepository, stockMovementRepository);
        }
    }
}
