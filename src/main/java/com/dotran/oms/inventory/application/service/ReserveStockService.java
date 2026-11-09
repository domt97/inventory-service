package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.application.event.StockReplyFactory;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.StockState;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ReserveStockUseCase;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.InventoryNotOrderableException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ReserveStock (ADR 0006, flow {@code order-stock-reservation.md}): all lines of one order or none (BR-INV-11), applied
 * once per order (BR-INV-07). One transaction; lock order: order header, then inventory rows in Java
 * {@link java.util.UUID#compareTo} order. Business outcomes are results; contract errors throw and write nothing.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
public class ReserveStockService implements ReserveStockUseCase {

    private static final Comparator<InventoryId> INVENTORY_ORDER = Comparator.comparing(InventoryId::getValue);

    private final StockReservationOrderRepository headerRepository;
    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final TransactionTimeoutRepository transactionTimeoutRepository;
    private final ReservationProperties properties;
    private final OutboxEventRepository outboxEventRepository;
    private final StockReplyFactory replyFactory;
    private final Clock clock;

    @Override
    @Transactional(timeoutString = ReservationProperties.TRANSACTION_TIMEOUT)
    public ReserveStockResult reserve(ReserveStockCmd cmd) {
        // One write point: every decision that returns publishes its reply in this transaction (duplicates too,
        // BR-INV-07); anything that throws rolls back and publishes nothing.
        ReserveStockResult result = decide(cmd);
        outboxEventRepository.saveAll(replyFactory.of(cmd, result));
        return result;
    }

    private ReserveStockResult decide(ReserveStockCmd cmd) {
        StockCommandValidation.requireCommon(cmd.getEventId(), cmd.getOccurredAt(), cmd.getMerchantId(),
                cmd.getStoreId(), cmd.getOrderId());
        StockCommandValidation.requireLines(cmd.getLines(), properties.getMaxLines());

        transactionTimeoutRepository.applyLocal(properties.getLockTimeout(), properties.getStatementTimeout());

        boolean created = headerRepository.insertIfAbsent(new StockReservationOrder(cmd.getOrderId(),
                cmd.getMerchantId(), cmd.getStoreId(), OrderReservationStatus.RESERVED, null, null));
        StockReservationOrder header = headerRepository
                .lockForUpdate(cmd.getOrderId(), cmd.getMerchantId(), cmd.getStoreId())
                .orElseThrow(() -> new StockCommandOwnershipMismatchException(
                        "Order " + cmd.getOrderId().getValue() + " belongs to another merchant or store"));

        // Duplicate check comes before the stale check: a successful reserve redelivered late is not a rejection.
        if (!created) {
            return answerDuplicate(cmd, header);
        }

        // Microseconds, as Postgres stores expires_at: a duplicate's answer (read back) equals the original's.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Duration age = Duration.between(cmd.getOccurredAt(), now);
        if (age.compareTo(properties.getTtl().plus(properties.getClockSkewTolerance())) > 0) {
            log.warn("Rejecting stale ReserveStock for order {}: {} old", cmd.getOrderId().getValue(), age);
            return reject(header, ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND));
        }

        Map<SKU, Inventory> inventoryBySku = inventoryRepository
                .getByMerchantStoreAndSkus(cmd.getMerchantId(), cmd.getStoreId(),
                        cmd.getLines().stream().map(ReserveStockCmd.Line::getSku).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Inventory::getSku, Function.identity()));

        Map<OrderItemId, LineRejectionReason> reasons = new LinkedHashMap<>();
        List<ReserveStockCmd.Line> known = new ArrayList<>();
        for (ReserveStockCmd.Line line : cmd.getLines()) {
            Inventory inventory = inventoryBySku.get(line.getSku());
            if (inventory == null || !inventory.getProductId().equals(line.getProductId())) {
                reasons.put(line.getOrderItemId(), LineRejectionReason.UNKNOWN_SKU);
            } else {
                known.add(line);
            }
        }

        List<ReserveStockCmd.Line> sorted = known.stream()
                .sorted(Comparator.comparing((ReserveStockCmd.Line line) -> inventoryBySku.get(line.getSku()).getId(),
                        INVENTORY_ORDER))
                .toList();

        // Domain pre-check on the loaded rows: two lines of the same SKU add up on the same in-memory object.
        for (ReserveStockCmd.Line line : sorted) {
            try {
                inventoryBySku.get(line.getSku()).reserve(line.getQuantity());
            } catch (InventoryNotOrderableException e) {
                reasons.put(line.getOrderItemId(), LineRejectionReason.NOT_ORDERABLE);
            } catch (InsufficientStockException e) {
                reasons.put(line.getOrderItemId(), LineRejectionReason.INSUFFICIENT_STOCK);
            }
        }
        if (!reasons.isEmpty()) {
            return reject(header, ReservationRejection.ofLines(rejectedLines(cmd, reasons)));
        }

        // Guarded updates: the SQL guard is what prevents oversell under concurrency.
        List<ReserveStockCmd.Line> held = new ArrayList<>();
        ReserveStockCmd.Line failed = null;
        for (ReserveStockCmd.Line line : sorted) {
            InventoryId inventoryId = inventoryBySku.get(line.getSku()).getId();
            int updated = inventoryRepository.reserve(inventoryId, cmd.getMerchantId(), cmd.getStoreId(),
                    line.getQuantity());
            if (updated == 0) {
                failed = line;
                break;
            }
            held.add(line);
        }
        if (failed != null) {
            releaseHeld(held, inventoryBySku);
            reasons.putAll(reasonsFromCurrentState(sorted, inventoryBySku));
            reasons.putIfAbsent(failed.getOrderItemId(), LineRejectionReason.INSUFFICIENT_STOCK);
            log.info("Reserve of order {} lost a race on {}", cmd.getOrderId().getValue(), failed.getSku().getValue());
            return reject(header, ReservationRejection.ofLines(rejectedLines(cmd, reasons)));
        }

        Duration ttl = properties.getTtl();
        List<StockReservation> reservations = sorted.stream()
                .map(line -> StockReservation.reserve(cmd.getMerchantId(), cmd.getStoreId(), cmd.getOrderId(),
                        line.getOrderItemId(), inventoryBySku.get(line.getSku()).getId(), line.getQuantity(), now, ttl))
                .toList();
        List<StockReservation> saved = stockReservationRepository.createBatch(reservations);
        List<StockReservationId> savedIds = saved.stream().map(StockReservation::getId).toList();

        int movements = stockMovementRepository.insertForReservations(savedIds, StockMovementType.RESERVE,
                ReservationStatus.RESERVED, null);
        if (movements != savedIds.size()) {
            throw new IllegalStateException("Expected " + savedIds.size() + " RESERVE movements, wrote " + movements);
        }

        Instant expiresAt = now.plus(ttl);
        log.info("Reserved {} lines for order {}", saved.size(), cmd.getOrderId().getValue());
        return new ReserveStockResult.Reserved(expiresAt, cmd.getLines().stream()
                .map(line -> new ReserveStockResult.Line(line.getOrderItemId(), line.getSku(), line.getQuantity(),
                        expiresAt))
                .toList());
    }

    /** A header existed before this command: answer from it without touching stock (BR-INV-07). */
    private ReserveStockResult answerDuplicate(ReserveStockCmd cmd, StockReservationOrder header) {
        if (header.status() == OrderReservationStatus.REJECTED) {
            return ReserveStockResult.Rejected.of(header.rejection());
        }
        List<ReservationLineView> views = stockReservationRepository
                .getLineViews(cmd.getMerchantId(), cmd.getStoreId(), cmd.getOrderId());
        if (views.isEmpty()) {
            // A tombstone: a release or confirm closed the order before any reserve.
            return new ReserveStockResult.Rejected(OrderRejectionReason.ORDER_CLOSED, List.of());
        }

        Map<OrderItemId, ReservationLineView> byItem = views.stream()
                .collect(Collectors.toMap(ReservationLineView::orderItemId, Function.identity()));
        boolean same = byItem.size() == cmd.getLines().size() && cmd.getLines().stream().allMatch(line -> {
            ReservationLineView view = byItem.get(line.getOrderItemId());
            return view != null && view.productId().equals(line.getProductId()) && view.sku().equals(line.getSku())
                    && view.quantity() == line.getQuantity();
        });
        if (!same) {
            throw new InvalidStockCommandException(
                    "Order " + cmd.getOrderId().getValue() + " was already reserved with other lines");
        }

        Instant expiresAt = views.stream().map(ReservationLineView::expiresAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        return new ReserveStockResult.Reserved(expiresAt, cmd.getLines().stream()
                .map(line -> {
                    ReservationLineView view = byItem.get(line.getOrderItemId());
                    return new ReserveStockResult.Line(view.orderItemId(), view.sku(), view.quantity(),
                            view.expiresAt());
                })
                .toList());
    }

    private ReserveStockResult reject(StockReservationOrder header, ReservationRejection rejection) {
        StockReservationOrder rejected = new StockReservationOrder(header.orderId(), header.merchantId(),
                header.storeId(), OrderReservationStatus.REJECTED, header.restock(), rejection);
        if (headerRepository.update(rejected, OrderReservationStatus.RESERVED) != 1) {
            throw new IllegalStateException("Order header " + header.orderId().getValue() + " changed while locked");
        }
        log.info("Reserve of order {} rejected: {}", header.orderId().getValue(), rejection);
        return ReserveStockResult.Rejected.of(rejection);
    }

    /** Lines already held in this transaction are released again (their rows are locked by this transaction). */
    private void releaseHeld(List<ReserveStockCmd.Line> held, Map<SKU, Inventory> inventoryBySku) {
        for (ReserveStockCmd.Line line : held) {
            InventoryId inventoryId = inventoryBySku.get(line.getSku()).getId();
            if (inventoryRepository.release(inventoryId, line.getQuantity()) == 0) {
                throw new IllegalStateException("Could not release a hold placed in the same transaction on " + inventoryId);
            }
        }
    }

    /** Reasons computed from the current rows (native read), lines taken in lock order. */
    private Map<OrderItemId, LineRejectionReason> reasonsFromCurrentState(List<ReserveStockCmd.Line> sorted,
                                                                          Map<SKU, Inventory> inventoryBySku) {
        Set<InventoryId> ids = sorted.stream().map(line -> inventoryBySku.get(line.getSku()).getId())
                .collect(Collectors.toSet());
        Map<InventoryId, StockState> states = inventoryRepository.getStockStates(ids).stream()
                .collect(Collectors.toMap(StockState::inventoryId, Function.identity()));
        Map<InventoryId, Long> remaining = new HashMap<>();
        Map<OrderItemId, LineRejectionReason> reasons = new LinkedHashMap<>();
        for (ReserveStockCmd.Line line : sorted) {
            InventoryId inventoryId = inventoryBySku.get(line.getSku()).getId();
            StockState state = states.get(inventoryId);
            if (state == null) {
                reasons.put(line.getOrderItemId(), LineRejectionReason.UNKNOWN_SKU);
                continue;
            }
            if (state.status() != InventoryStatus.ACTIVE) {
                reasons.put(line.getOrderItemId(), LineRejectionReason.NOT_ORDERABLE);
                continue;
            }
            long left = remaining.getOrDefault(inventoryId, state.available());
            if (left < line.getQuantity()) {
                reasons.put(line.getOrderItemId(), LineRejectionReason.INSUFFICIENT_STOCK);
            } else {
                remaining.put(inventoryId, left - line.getQuantity());
            }
        }
        return reasons;
    }

    private static List<ReservationRejection.Line> rejectedLines(ReserveStockCmd cmd,
                                                                 Map<OrderItemId, LineRejectionReason> reasons) {
        return cmd.getLines().stream()
                .filter(line -> reasons.containsKey(line.getOrderItemId()))
                .map(line -> new ReservationRejection.Line(line.getOrderItemId(), line.getSku(),
                        reasons.get(line.getOrderItemId())))
                .toList();
    }
}
