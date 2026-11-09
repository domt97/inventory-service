package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.application.event.OutboxMessage;
import com.dotran.oms.inventory.application.event.StockReplyFactory;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.StockState;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservation;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

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
@DisplayName("ReserveStockService")
class ReserveStockServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    // Inventory ids chosen so that UUID.compareTo order is LOW < HIGH.
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

    private ReservationProperties properties;
    private ReserveStockService service;

    private final MerchantId merchantId = MerchantId.of(UUID.randomUUID());
    private final StoreId storeId = StoreId.of(UUID.randomUUID());
    private final OrderId orderId = OrderId.of(UUID.randomUUID());
    private final ProductId productId = ProductId.of(UUID.randomUUID());
    private final OrderItemId item1 = OrderItemId.of(UUID.randomUUID());
    private final OrderItemId item2 = OrderItemId.of(UUID.randomUUID());
    private final SKU skuA = SKU.of("SKU-A");
    private final SKU skuB = SKU.of("SKU-B");

    @BeforeEach
    void setUp() {
        properties = new ReservationProperties();
        service = new ReserveStockService(headerRepository, stockReservationRepository, inventoryRepository,
                stockMovementRepository, transactionTimeoutRepository, properties, outboxEventRepository,
                replyFactory, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- fixtures ----

    private ReserveStockCmd.Line line(OrderItemId item, SKU sku, long quantity) {
        return ReserveStockCmd.Line.builder().orderItemId(item).productId(productId).sku(sku).quantity(quantity).build();
    }

    private ReserveStockCmd cmd(Instant occurredAt, List<ReserveStockCmd.Line> lines) {
        return ReserveStockCmd.builder()
                .eventId(EventId.newEventId())
                .occurredAt(occurredAt)
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .lines(lines)
                .build();
    }

    private ReserveStockCmd cmd(List<ReserveStockCmd.Line> lines) {
        return cmd(NOW.minusSeconds(60), lines);
    }

    private Inventory inventory(InventoryId id, SKU sku, long quantity, long reserved, InventoryStatus status) {
        return Inventory.builder()
                .id(id)
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(productId)
                .sku(sku)
                .quantity(quantity)
                .reservedQuantity(reserved)
                .status(status)
                .build();
    }

    private StockReservationOrder header(OrderReservationStatus status, ReservationRejection rejection) {
        return new StockReservationOrder(orderId, merchantId, storeId, status, null, rejection);
    }

    /** A new header: insert creates it, the lock returns it as RESERVED. */
    private void newHeader() {
        when(headerRepository.insertIfAbsent(any())).thenReturn(true);
        when(headerRepository.lockForUpdate(orderId, merchantId, storeId))
                .thenReturn(Optional.of(header(OrderReservationStatus.RESERVED, null)));
    }

    private void existingHeader(StockReservationOrder header) {
        when(headerRepository.insertIfAbsent(any())).thenReturn(false);
        when(headerRepository.lockForUpdate(orderId, merchantId, storeId)).thenReturn(Optional.of(header));
    }

    private void inventories(Inventory... rows) {
        when(inventoryRepository.getByMerchantStoreAndSkus(eq(merchantId), eq(storeId), anyCollection()))
                .thenReturn(List.of(rows));
    }

    /** createBatch returns the reservations with ids 1, 2, ... in the order given. */
    private void savesReservations() {
        when(stockReservationRepository.createBatch(anyList())).thenAnswer(invocation -> {
            List<StockReservation> in = invocation.getArgument(0);
            List<StockReservation> out = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                StockReservation r = in.get(i);
                out.add(StockReservation.builder()
                        .id(StockReservationId.of((long) i + 1))
                        .merchantId(r.getMerchantId()).storeId(r.getStoreId()).orderId(r.getOrderId())
                        .orderItemId(r.getOrderItemId()).inventoryId(r.getInventoryId()).quantity(r.getQuantity())
                        .status(r.getStatus()).expiresAt(r.getExpiresAt())
                        .createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt())
                        .build());
            }
            return out;
        });
    }

    private ReservationRejection storedRejection() {
        ArgumentCaptor<StockReservationOrder> captor = ArgumentCaptor.forClass(StockReservationOrder.class);
        verify(headerRepository).update(captor.capture(), eq(OrderReservationStatus.RESERVED));
        assertThat(captor.getValue().status()).isEqualTo(OrderReservationStatus.REJECTED);
        return captor.getValue().rejection();
    }

    // ---- tests ----

    @Nested
    @DisplayName("successful reserve")
    class Success {

        @Test
        @DisplayName("sets the timeouts, creates and locks the header, reserves in UUID order, writes reservations and RESERVE movements")
        void reservesAllLines() {
            newHeader();
            // Command lists HIGH first; the guarded updates must still run LOW then HIGH.
            inventories(inventory(HIGH, skuB, 10, 0, InventoryStatus.ACTIVE),
                    inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(any(), eq(merchantId), eq(storeId), anyLong())).thenReturn(1);
            savesReservations();
            when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(2);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuB, 2), line(item2, skuA, 3))));

            InOrder inOrder = inOrder(transactionTimeoutRepository, headerRepository, inventoryRepository,
                    stockReservationRepository, stockMovementRepository);
            inOrder.verify(transactionTimeoutRepository).applyLocal(Duration.ofSeconds(3), Duration.ofSeconds(5));
            inOrder.verify(headerRepository).insertIfAbsent(header(OrderReservationStatus.RESERVED, null));
            inOrder.verify(headerRepository).lockForUpdate(orderId, merchantId, storeId);
            inOrder.verify(inventoryRepository).reserve(LOW, merchantId, storeId, 3L);
            inOrder.verify(inventoryRepository).reserve(HIGH, merchantId, storeId, 2L);
            inOrder.verify(stockReservationRepository).createBatch(anyList());
            inOrder.verify(stockMovementRepository).insertForReservations(
                    List.of(StockReservationId.of(1L), StockReservationId.of(2L)),
                    StockMovementType.RESERVE, ReservationStatus.RESERVED, null);
            verify(headerRepository, never()).update(any(), any());

            assertThat(result).isInstanceOf(ReserveStockResult.Reserved.class);
            ReserveStockResult.Reserved reserved = (ReserveStockResult.Reserved) result;
            assertThat(reserved.expiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(reserved.lines()).containsExactly(
                    new ReserveStockResult.Line(item1, skuB, 2, NOW.plus(TTL)),
                    new ReserveStockResult.Line(item2, skuA, 3, NOW.plus(TTL)));

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("reservations expire after the configured ttl")
        void usesConfiguredTtl() {
            properties.setTtl(Duration.ofMinutes(5));
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(1);
            savesReservations();
            when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(1);

            service.reserve(cmd(List.of(line(item1, skuA, 1))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StockReservation>> captor = ArgumentCaptor.forClass(List.class);
            verify(stockReservationRepository).createBatch(captor.capture());
            assertThat(captor.getValue()).singleElement()
                    .satisfies(r -> assertThat(r.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5))));

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("throws when fewer RESERVE movements are written than reservations")
        void movementShortfallThrows() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(1);
            savesReservations();
            when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(0);

            assertThrows(IllegalStateException.class, () -> service.reserve(cmd(List.of(line(item1, skuA, 1)))));

            verify(outboxEventRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("reply in the outbox")
    class Reply {

        @Test
        @DisplayName("the reply is built from the command and the returned result, and written after the header update")
        void replyAfterDecision() {
            newHeader();
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);
            // A sentinel list (not Mockito's default empty list) proves the factory's messages are the ones saved.
            List<OutboxMessage> messages = List.of(new OutboxMessage(UUID.randomUUID(), OutboxMessage.AGGREGATE_TYPE,
                    orderId.getValue(), "StockReservationRejected", merchantId.getValue(), null));
            ReserveStockCmd command = cmd(NOW.minus(Duration.ofHours(1)), List.of(line(item1, skuA, 1)));
            when(replyFactory.of(eq(command), any(ReserveStockResult.class))).thenReturn(messages);

            ReserveStockResult result = service.reserve(command);

            InOrder inOrder = inOrder(headerRepository, replyFactory, outboxEventRepository);
            inOrder.verify(headerRepository).update(any(), eq(OrderReservationStatus.RESERVED));
            inOrder.verify(replyFactory).of(command, result);
            inOrder.verify(outboxEventRepository).saveAll(messages);
        }

        @Test
        @DisplayName("expiresAt is truncated to microseconds, as Postgres stores it")
        void expiryInMicros() {
            ReserveStockService nanoService = new ReserveStockService(headerRepository, stockReservationRepository,
                    inventoryRepository, stockMovementRepository, transactionTimeoutRepository, properties,
                    outboxEventRepository, replyFactory,
                    Clock.fixed(Instant.parse("2026-11-07T10:00:00.123456789Z"), ZoneOffset.UTC));
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(1);
            savesReservations();
            when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(1);

            ReserveStockResult result = nanoService.reserve(cmd(List.of(line(item1, skuA, 1))));

            assertThat(((ReserveStockResult.Reserved) result).expiresAt())
                    .isEqualTo(Instant.parse("2026-11-07T10:15:00.123456Z"));
            verify(outboxEventRepository).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("contract errors (nothing written)")
    class ContractErrors {

        @Test
        @DisplayName("no lines")
        void noLines() {
            assertThrows(InvalidStockCommandException.class, () -> service.reserve(cmd(List.of())));
            verifyNoInteractions(headerRepository, inventoryRepository, stockReservationRepository,
                    stockMovementRepository, transactionTimeoutRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("more lines than max-lines (BR-INV-11)")
        void tooManyLines() {
            List<ReserveStockCmd.Line> lines = IntStream.range(0, 101)
                    .mapToObj(i -> line(OrderItemId.of(UUID.randomUUID()), SKU.of("SKU-" + i), 1))
                    .toList();

            assertThrows(InvalidStockCommandException.class, () -> service.reserve(cmd(lines)));
            verifyNoInteractions(headerRepository, transactionTimeoutRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("duplicate orderItemId")
        void duplicateOrderItem() {
            assertThrows(InvalidStockCommandException.class,
                    () -> service.reserve(cmd(List.of(line(item1, skuA, 1), line(item1, skuB, 1)))));
            verifyNoInteractions(headerRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("quantity below 1")
        void zeroQuantity() {
            assertThrows(InvalidStockCommandException.class,
                    () -> service.reserve(cmd(List.of(line(item1, skuA, 0)))));
            verifyNoInteractions(headerRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("missing occurredAt")
        void missingOccurredAt() {
            assertThrows(InvalidStockCommandException.class,
                    () -> service.reserve(cmd(null, List.of(line(item1, skuA, 1)))));
            verifyNoInteractions(headerRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("order header owned by another merchant or store")
        void ownershipMismatch() {
            when(headerRepository.insertIfAbsent(any())).thenReturn(false);
            when(headerRepository.lockForUpdate(orderId, merchantId, storeId)).thenReturn(Optional.empty());

            assertThrows(StockCommandOwnershipMismatchException.class,
                    () -> service.reserve(cmd(List.of(line(item1, skuA, 1)))));
            verifyNoInteractions(inventoryRepository, stockReservationRepository, stockMovementRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("duplicates (BR-INV-07)")
    class Duplicates {

        private ReservationLineView view(OrderItemId item, InventoryId inventoryId, SKU sku, long quantity,
                                         ReservationStatus status, Instant expiresAt) {
            return new ReservationLineView(StockReservationId.of(1L), item, inventoryId, productId, sku, quantity,
                    status, expiresAt);
        }

        @Test
        @DisplayName("same lines: answers Reserved from the stored lines, even when the command is older than the ttl")
        void sameLinesAnswersReserved() {
            Instant expiresAt = NOW.minusSeconds(10);
            existingHeader(header(OrderReservationStatus.CONFIRMED, null));
            when(stockReservationRepository.getLineViews(merchantId, storeId, orderId)).thenReturn(List.of(
                    view(item1, LOW, skuA, 2, ReservationStatus.CONFIRMED, expiresAt)));

            ReserveStockResult result = service.reserve(
                    cmd(NOW.minus(Duration.ofHours(2)), List.of(line(item1, skuA, 2))));

            assertThat(result).isEqualTo(new ReserveStockResult.Reserved(expiresAt,
                    List.of(new ReserveStockResult.Line(item1, skuA, 2, expiresAt))));
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
            verify(headerRepository, never()).update(any(), any());
            verify(stockReservationRepository, never()).createBatch(anyList());

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("different quantity: contract error")
        void differentLinesThrows() {
            existingHeader(header(OrderReservationStatus.RESERVED, null));
            when(stockReservationRepository.getLineViews(merchantId, storeId, orderId)).thenReturn(List.of(
                    view(item1, LOW, skuA, 2, ReservationStatus.RESERVED, NOW)));

            assertThrows(InvalidStockCommandException.class,
                    () -> service.reserve(cmd(List.of(line(item1, skuA, 3)))));
            verifyNoInteractions(inventoryRepository, stockMovementRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("an extra line: contract error")
        void extraLineThrows() {
            existingHeader(header(OrderReservationStatus.RESERVED, null));
            when(stockReservationRepository.getLineViews(merchantId, storeId, orderId)).thenReturn(List.of(
                    view(item1, LOW, skuA, 2, ReservationStatus.RESERVED, NOW)));

            assertThrows(InvalidStockCommandException.class,
                    () -> service.reserve(cmd(List.of(line(item1, skuA, 2), line(item2, skuB, 1)))));

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("header REJECTED: answers the stored rejection without comparing lines")
        void rejectedAnswersStoredRejection() {
            ReservationRejection rejection = ReservationRejection.ofLines(List.of(
                    new ReservationRejection.Line(item1, skuA, LineRejectionReason.INSUFFICIENT_STOCK)));
            existingHeader(header(OrderReservationStatus.REJECTED, rejection));

            ReserveStockResult result = service.reserve(cmd(List.of(line(item2, skuB, 9))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, rejection.lines()));
            verifyNoInteractions(stockReservationRepository, inventoryRepository, stockMovementRepository);

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("tombstone (closed header without reservations): Rejected ORDER_CLOSED, nothing held")
        void tombstoneAnswersOrderClosed() {
            existingHeader(header(OrderReservationStatus.CANCELLED, null));
            when(stockReservationRepository.getLineViews(merchantId, storeId, orderId)).thenReturn(List.of());

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 1))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(OrderRejectionReason.ORDER_CLOSED, List.of()));
            verifyNoInteractions(inventoryRepository, stockMovementRepository);
            verify(headerRepository, never()).update(any(), any());

            verify(outboxEventRepository).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("stale command (BR-INV-11)")
    class Stale {

        @Test
        @DisplayName("older than ttl + tolerance: header REJECTED with STALE_COMMAND, inventory untouched")
        void staleIsRejected() {
            newHeader();
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(NOW.minus(TTL).minusSeconds(31),
                    List.of(line(item1, skuA, 1))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(OrderRejectionReason.STALE_COMMAND, List.of()));
            assertThat(storedRejection()).isEqualTo(ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND));
            verifyNoInteractions(inventoryRepository, stockReservationRepository, stockMovementRepository);

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("exactly ttl + tolerance old is still accepted")
        void boundaryIsAccepted() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(1);
            savesReservations();
            when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(NOW.minus(TTL).minusSeconds(30),
                    List.of(line(item1, skuA, 1))));

            assertThat(result).isInstanceOf(ReserveStockResult.Reserved.class);

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("an occurredAt in the future (clock skew) is not stale")
        void futureIsAccepted() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(1);
            savesReservations();
            when(stockMovementRepository.insertForReservations(anyList(), any(), any(), any())).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(NOW.plusSeconds(20), List.of(line(item1, skuA, 1))));

            assertThat(result).isInstanceOf(ReserveStockResult.Reserved.class);

            verify(outboxEventRepository).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("business rejections (header REJECTED, nothing held)")
    class Rejections {

        @Test
        @DisplayName("missing inventory row or another product's SKU → UNKNOWN_SKU (BR-CAT-04)")
        void unknownSku() {
            newHeader();
            Inventory otherProduct = Inventory.builder().id(HIGH).merchantId(merchantId).storeId(storeId)
                    .productId(ProductId.of(UUID.randomUUID())).sku(skuB).quantity(10L).reservedQuantity(0L)
                    .status(InventoryStatus.ACTIVE).build();
            inventories(otherProduct);
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 1), line(item2, skuB, 1))));

            List<ReservationRejection.Line> expected = List.of(
                    new ReservationRejection.Line(item1, skuA, LineRejectionReason.UNKNOWN_SKU),
                    new ReservationRejection.Line(item2, skuB, LineRejectionReason.UNKNOWN_SKU));
            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, expected));
            assertThat(storedRejection()).isEqualTo(ReservationRejection.ofLines(expected));
            verify(inventoryRepository, never()).reserve(any(), any(), any(), anyLong());
            verifyNoInteractions(stockReservationRepository, stockMovementRepository);

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("INACTIVE → NOT_ORDERABLE and short stock → INSUFFICIENT_STOCK, reported per line, no guarded update")
        void preCheckReasons() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.INACTIVE),
                    inventory(HIGH, skuB, 1, 0, InventoryStatus.ACTIVE));
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 1), line(item2, skuB, 2))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, List.of(
                    new ReservationRejection.Line(item1, skuA, LineRejectionReason.NOT_ORDERABLE),
                    new ReservationRejection.Line(item2, skuB, LineRejectionReason.INSUFFICIENT_STOCK))));
            verify(inventoryRepository, never()).reserve(any(), any(), any(), anyLong());

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("two lines of the same SKU add up in the pre-check")
        void sameSkuLinesAddUp() {
            newHeader();
            inventories(inventory(LOW, skuA, 5, 0, InventoryStatus.ACTIVE));
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 3), line(item2, skuA, 3))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, List.of(
                    new ReservationRejection.Line(item2, skuA, LineRejectionReason.INSUFFICIENT_STOCK))));

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("lost race on a guarded update: releases the lines already held, reasons from the current rows")
        void lostRace() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE),
                    inventory(HIGH, skuB, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 2L)).thenReturn(1);
            when(inventoryRepository.reserve(HIGH, merchantId, storeId, 4L)).thenReturn(0);
            when(inventoryRepository.release(LOW, 2L)).thenReturn(1);
            // After the release LOW is whole again; HIGH was sold out by another order meanwhile.
            when(inventoryRepository.getStockStates(anyCollection())).thenReturn(List.of(
                    new StockState(LOW, InventoryStatus.ACTIVE, 10),
                    new StockState(HIGH, InventoryStatus.ACTIVE, 1)));
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 2), line(item2, skuB, 4))));

            InOrder inOrder = inOrder(inventoryRepository);
            inOrder.verify(inventoryRepository).reserve(LOW, merchantId, storeId, 2L);
            inOrder.verify(inventoryRepository).reserve(HIGH, merchantId, storeId, 4L);
            inOrder.verify(inventoryRepository).release(LOW, 2L);
            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, List.of(
                    new ReservationRejection.Line(item2, skuB, LineRejectionReason.INSUFFICIENT_STOCK))));
            verifyNoInteractions(stockReservationRepository, stockMovementRepository);

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("a hold placed in the same transaction that cannot be released again is an internal error")
        void releaseOfHeldLineFails() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE),
                    inventory(HIGH, skuB, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 2L)).thenReturn(1);
            when(inventoryRepository.reserve(HIGH, merchantId, storeId, 4L)).thenReturn(0);
            when(inventoryRepository.release(LOW, 2L)).thenReturn(0);

            assertThrows(IllegalStateException.class,
                    () -> service.reserve(cmd(List.of(line(item1, skuA, 2), line(item2, skuB, 4)))));
            verify(headerRepository, never()).update(any(), any());
            verifyNoInteractions(stockReservationRepository, stockMovementRepository);

            verify(outboxEventRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("lost race against a deactivation: NOT_ORDERABLE from the current row")
        void lostRaceToDeactivation() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(0);
            when(inventoryRepository.getStockStates(anyCollection())).thenReturn(List.of(
                    new StockState(LOW, InventoryStatus.INACTIVE, 10)));
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 1))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, List.of(
                    new ReservationRejection.Line(item1, skuA, LineRejectionReason.NOT_ORDERABLE))));
            verify(inventoryRepository, never()).release(any(), anyLong());

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("lost race but the current row looks fine: the failed line defaults to INSUFFICIENT_STOCK")
        void lostRaceDefaultsReason() {
            newHeader();
            inventories(inventory(LOW, skuA, 10, 0, InventoryStatus.ACTIVE));
            when(inventoryRepository.reserve(LOW, merchantId, storeId, 1L)).thenReturn(0);
            when(inventoryRepository.getStockStates(anyCollection())).thenReturn(List.of(
                    new StockState(LOW, InventoryStatus.ACTIVE, 10)));
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(1);

            ReserveStockResult result = service.reserve(cmd(List.of(line(item1, skuA, 1))));

            assertThat(result).isEqualTo(new ReserveStockResult.Rejected(null, List.of(
                    new ReservationRejection.Line(item1, skuA, LineRejectionReason.INSUFFICIENT_STOCK))));

            verify(outboxEventRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("a header that changed while locked is an internal error")
        void headerUpdateLost() {
            newHeader();
            when(headerRepository.update(any(), eq(OrderReservationStatus.RESERVED))).thenReturn(0);

            assertThrows(IllegalStateException.class, () -> service.reserve(
                    cmd(NOW.minus(Duration.ofHours(1)), List.of(line(item1, skuA, 1)))));

            verify(outboxEventRepository, never()).saveAll(anyList());
        }
    }
}
