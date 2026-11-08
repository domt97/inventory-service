package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ReleaseStockReservationUseCase;
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

/**
 * ReleaseStockReservation (ADR 0006): closes the order's stock lifecycle. RESERVED lines are released
 * (RESERVE_RELEASE); CONFIRMED lines are returned to stock (RETURN) when {@code restock}, otherwise they stay sold
 * (BR-INV-03/04, BR-ORD-09). The first release fixes {@code restock}; later ones re-answer and change nothing.
 *
 * <p>Lock order: order header, then RESERVED reservations (ascending id) and CONFIRMED reservations (ascending id) —
 * two disjoint sets, and expiry only touches RESERVED rows, so no cycle — then inventories in Java UUID order, one pass.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
public class ReleaseStockReservationService implements ReleaseStockReservationUseCase {

    private final StockReservationOrderRepository headerRepository;
    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final TransactionTimeoutRepository transactionTimeoutRepository;
    private final ReservationProperties properties;

    @Override
    @Transactional(timeoutString = ReservationProperties.TRANSACTION_TIMEOUT)
    public ReleaseStockReservationResult release(ReleaseStockReservationCmd cmd) {
        StockCommandValidation.requireCommon(cmd.getEventId(), cmd.getOccurredAt(), cmd.getMerchantId(),
                cmd.getStoreId(), cmd.getOrderId());
        StockCommandValidation.requireRelease(cmd.getRestock(), cmd.getReason());
        boolean restock = cmd.getRestock();

        transactionTimeoutRepository.applyLocal(properties.getLockTimeout(), properties.getStatementTimeout());

        // No header yet: close the order (tombstone) so a late reserve is rejected ORDER_CLOSED.
        boolean created = headerRepository.insertIfAbsent(new StockReservationOrder(cmd.getOrderId(),
                cmd.getMerchantId(), cmd.getStoreId(), OrderReservationStatus.CANCELLED, restock, null));
        StockReservationOrder header = headerRepository
                .lockForUpdate(cmd.getOrderId(), cmd.getMerchantId(), cmd.getStoreId())
                .orElseThrow(() -> new StockCommandOwnershipMismatchException(
                        "Order " + cmd.getOrderId().getValue() + " belongs to another merchant or store"));
        if (created) {
            log.info("Release before any reserve for order {}: order closed", cmd.getOrderId().getValue());
            return new ReleaseStockReservationResult(restock, List.of());
        }

        List<ReservationLineView> views = stockReservationRepository
                .getLineViews(cmd.getMerchantId(), cmd.getStoreId(), cmd.getOrderId());

        if (header.isClosed()) {
            return answerClosed(cmd, header, views, restock);
        }

        List<StockReservationId> reservedIds = idsWithStatus(views, ReservationStatus.RESERVED);
        List<StockReservationId> confirmedIds = idsWithStatus(views, ReservationStatus.CONFIRMED);

        Set<StockReservationId> released = reservedIds.isEmpty()
                ? Set.of() : stockReservationRepository.markReleased(reservedIds);
        Set<StockReservationId> returned = (!restock || confirmedIds.isEmpty())
                ? Set.of() : stockReservationRepository.markReturned(confirmedIds);
        if (returned.size() < (restock ? confirmedIds.size() : 0)) {
            throw new InvalidReservationStateException(
                    "Confirmed reservations of order " + cmd.getOrderId().getValue() + " were modified concurrently");
        }

        // One pass over the inventories in Java UUID order.
        Map<InventoryId, long[]> byInventory = new TreeMap<>(Comparator.comparing(InventoryId::getValue));
        for (ReservationLineView view : views) {
            if (released.contains(view.id())) {
                byInventory.computeIfAbsent(view.inventoryId(), id -> new long[2])[0] += view.quantity();
            } else if (returned.contains(view.id())) {
                byInventory.computeIfAbsent(view.inventoryId(), id -> new long[2])[1] += view.quantity();
            }
        }
        for (Map.Entry<InventoryId, long[]> entry : byInventory.entrySet()) {
            long releaseQuantity = entry.getValue()[0];
            long returnQuantity = entry.getValue()[1];
            if (releaseQuantity > 0 && inventoryRepository.release(entry.getKey(), releaseQuantity) == 0) {
                log.error("Failed to release inventory {}", entry.getKey().getValue());
                throw new InvalidReservationException();
            }
            if (returnQuantity > 0 && inventoryRepository.stockIn(entry.getKey(), cmd.getMerchantId(),
                    cmd.getStoreId(), returnQuantity) == 0) {
                throw new IllegalStateException("Could not return stock to inventory " + entry.getKey().getValue());
            }
        }

        writeMovements(released, StockMovementType.RESERVE_RELEASE, ReservationStatus.RELEASED, cmd.getReason());
        writeMovements(returned, StockMovementType.RETURN, ReservationStatus.RETURNED, cmd.getReason());

        StockReservationOrder closed = new StockReservationOrder(header.orderId(), header.merchantId(),
                header.storeId(), OrderReservationStatus.CANCELLED, restock, header.rejection());
        if (headerRepository.update(closed, header.status()) != 1) {
            throw new IllegalStateException("Order header " + header.orderId().getValue() + " changed while locked");
        }

        log.info("Released order {}: {} holds released, {} lines returned to stock (restock={})",
                cmd.getOrderId().getValue(), released.size(), returned.size(), restock);
        return new ReleaseStockReservationResult(restock, views.stream()
                .map(view -> {
                    if (released.contains(view.id())) {
                        return new ReleaseStockReservationResult.Line(view.orderItemId(), ReservationStatus.RELEASED, false);
                    }
                    if (returned.contains(view.id())) {
                        return new ReleaseStockReservationResult.Line(view.orderItemId(), ReservationStatus.RETURNED, true);
                    }
                    // A RESERVED line that lost against a concurrent expiry is reported EXPIRED.
                    ReservationStatus status = view.status() == ReservationStatus.RESERVED
                            ? ReservationStatus.EXPIRED : view.status();
                    return new ReleaseStockReservationResult.Line(view.orderItemId(), status, false);
                })
                .toList());
    }

    /** Header REJECTED or CANCELLED: change no stock; the first release still fixes {@code restock}. */
    private ReleaseStockReservationResult answerClosed(ReleaseStockReservationCmd cmd, StockReservationOrder header,
                                                       List<ReservationLineView> views, boolean restock) {
        boolean storedRestock;
        if (header.isFirstRelease()) {
            StockReservationOrder withRestock = new StockReservationOrder(header.orderId(), header.merchantId(),
                    header.storeId(), header.status(), restock, header.rejection());
            if (headerRepository.update(withRestock, header.status()) != 1) {
                throw new IllegalStateException("Order header " + header.orderId().getValue() + " changed while locked");
            }
            storedRestock = restock;
        } else {
            storedRestock = header.restock();
            if (storedRestock != restock) {
                log.warn("Release of order {} asks restock={} but the first release fixed restock={}; ignored",
                        cmd.getOrderId().getValue(), restock, storedRestock);
            }
        }
        return new ReleaseStockReservationResult(storedRestock, views.stream()
                .map(view -> new ReleaseStockReservationResult.Line(view.orderItemId(), view.status(),
                        view.status() == ReservationStatus.RETURNED))
                .toList());
    }

    private void writeMovements(Set<StockReservationId> ids, StockMovementType type, ReservationStatus status,
                                String reason) {
        if (ids.isEmpty()) {
            return;
        }
        int written = stockMovementRepository.insertForReservations(ids, type, status, reason);
        if (written != ids.size()) {
            throw new IllegalStateException("Expected " + ids.size() + " " + type + " movements, wrote " + written);
        }
    }

    private static List<StockReservationId> idsWithStatus(List<ReservationLineView> views, ReservationStatus status) {
        return views.stream().filter(v -> v.status() == status).map(ReservationLineView::id).toList();
    }
}
