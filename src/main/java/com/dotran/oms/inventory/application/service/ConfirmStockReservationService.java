package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReservationLineStatus;
import com.dotran.oms.inventory.application.event.StockReplyFactory;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ConfirmStockReservationUseCase;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * ConfirmStockReservation (ADR 0006): the order is PLACED, every held line becomes CONFIRMED and its quantity is
 * deducted with a SALE movement (BR-INV-04). Applied once per order (BR-INV-07). Lock order: order header, reservations
 * (ascending id), inventories (Java UUID order). Business outcomes are results; only a lost guarded update (e.g. a
 * concurrent expiry) throws, and the retry then answers NOT_RESERVED.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
public class ConfirmStockReservationService implements ConfirmStockReservationUseCase {

    private final StockReservationOrderRepository headerRepository;
    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final TransactionTimeoutRepository transactionTimeoutRepository;
    private final ReservationProperties properties;
    private final OutboxEventRepository outboxEventRepository;
    private final StockReplyFactory replyFactory;

    @Override
    @Transactional(timeoutString = ReservationProperties.TRANSACTION_TIMEOUT)
    public ConfirmStockReservationResult confirm(ConfirmStockReservationCmd cmd) {
        // One write point: every decision that returns publishes its reply in this transaction (duplicates too,
        // BR-INV-07); anything that throws rolls back and publishes nothing.
        ConfirmStockReservationResult result = decide(cmd);
        outboxEventRepository.saveAll(replyFactory.of(cmd, result));
        return result;
    }

    private ConfirmStockReservationResult decide(ConfirmStockReservationCmd cmd) {
        StockCommandValidation.requireCommon(cmd.getEventId(), cmd.getOccurredAt(), cmd.getMerchantId(),
                cmd.getStoreId(), cmd.getOrderId());

        transactionTimeoutRepository.applyLocal(properties.getLockTimeout(), properties.getStatementTimeout());

        // No header yet: close the order (tombstone) so a late reserve is rejected ORDER_CLOSED.
        boolean created = headerRepository.insertIfAbsent(new StockReservationOrder(cmd.getOrderId(),
                cmd.getMerchantId(), cmd.getStoreId(), OrderReservationStatus.CANCELLED, null, null));
        StockReservationOrder header = headerRepository
                .lockForUpdate(cmd.getOrderId(), cmd.getMerchantId(), cmd.getStoreId())
                .orElseThrow(() -> new StockCommandOwnershipMismatchException(
                        "Order " + cmd.getOrderId().getValue() + " belongs to another merchant or store"));
        if (created) {
            log.warn("Confirm before any reserve for order {}: order closed", cmd.getOrderId().getValue());
            return new ConfirmStockReservationResult.ConfirmationFailed(
                    ConfirmStockReservationResult.Reason.UNKNOWN_ORDER, List.of());
        }

        List<ReservationLineView> views = stockReservationRepository
                .getLineViews(cmd.getMerchantId(), cmd.getStoreId(), cmd.getOrderId());
        List<ReservationLineStatus> statuses = views.stream()
                .map(v -> new ReservationLineStatus(v.orderItemId(), v.status()))
                .toList();

        switch (header.status()) {
            case REJECTED, CANCELLED -> {
                return new ConfirmStockReservationResult.ConfirmationFailed(
                        ConfirmStockReservationResult.Reason.ORDER_CLOSED, statuses);
            }
            case CONFIRMED -> {
                return new ConfirmStockReservationResult.Confirmed(statuses);
            }
            default -> {
                // RESERVED: handled below
            }
        }

        if (views.isEmpty() || views.stream().anyMatch(v -> v.status() != ReservationStatus.RESERVED)) {
            return new ConfirmStockReservationResult.ConfirmationFailed(
                    ConfirmStockReservationResult.Reason.NOT_RESERVED, statuses);
        }

        List<StockReservationId> ids = views.stream().map(ReservationLineView::id).toList();
        Set<StockReservationId> confirmed = stockReservationRepository.markConfirmed(ids);
        if (confirmed.size() < ids.size()) {
            // Lost against a concurrent expiry: roll back everything; the retry answers NOT_RESERVED.
            throw new InvalidReservationStateException(
                    "Stock reservations of order " + cmd.getOrderId().getValue() + " were modified concurrently");
        }

        Map<InventoryId, Long> quantityByInventory = views.stream()
                .collect(Collectors.groupingBy(
                        ReservationLineView::inventoryId,
                        () -> new TreeMap<>(Comparator.comparing(InventoryId::getValue)),
                        Collectors.summingLong(ReservationLineView::quantity)));
        for (Map.Entry<InventoryId, Long> entry : quantityByInventory.entrySet()) {
            if (inventoryRepository.confirm(entry.getKey(), entry.getValue()) == 0) {
                log.error("Failed to confirm inventory {}", entry.getKey().getValue());
                throw new InvalidReservationException();
            }
        }

        int movements = stockMovementRepository.insertForReservations(ids, StockMovementType.SALE,
                ReservationStatus.CONFIRMED, null);
        if (movements != ids.size()) {
            throw new IllegalStateException("Expected " + ids.size() + " SALE movements, wrote " + movements);
        }

        StockReservationOrder confirmedHeader = new StockReservationOrder(header.orderId(), header.merchantId(),
                header.storeId(), OrderReservationStatus.CONFIRMED, header.restock(), header.rejection());
        if (headerRepository.update(confirmedHeader, OrderReservationStatus.RESERVED) != 1) {
            throw new IllegalStateException("Order header " + header.orderId().getValue() + " changed while locked");
        }

        log.info("Confirmed {} lines for order {}", ids.size(), cmd.getOrderId().getValue());
        return new ConfirmStockReservationResult.Confirmed(views.stream()
                .map(v -> new ReservationLineStatus(v.orderItemId(), ReservationStatus.CONFIRMED))
                .toList());
    }
}
