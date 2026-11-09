package com.dotran.oms.inventory.application.event;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReservationLineStatus;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.model.StockReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the stock replies (ADR 0006) from a command and its result, and the expiry events. A new eventId per message
 * (a duplicate command gets a new reply, BR-INV-07); {@code causationId} = the command's eventId.
 *
 * <p>Command paths throw {@link IllegalStateException} for a state the reply schema cannot carry (an internal error:
 * the transaction rolls back). The expiry path never throws: one bad row must not block the expiry of a whole SKU.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReplyFactory {

    private static final Set<ReservationStatus> FAILED_STATUSES = EnumSet.of(ReservationStatus.RESERVED,
            ReservationStatus.CONFIRMED, ReservationStatus.RELEASED, ReservationStatus.EXPIRED,
            ReservationStatus.RETURNED);
    private static final Set<ReservationStatus> RELEASED_STATUSES = EnumSet.of(ReservationStatus.RELEASED,
            ReservationStatus.RETURNED, ReservationStatus.CONFIRMED, ReservationStatus.EXPIRED);

    private final Clock clock;

    public List<OutboxMessage> of(ReserveStockCmd cmd, ReserveStockResult result) {
        Instant now = clock.instant();
        UUID merchantId = cmd.getMerchantId().getValue();
        UUID storeId = cmd.getStoreId().getValue();
        UUID orderId = cmd.getOrderId().getValue();
        UUID causationId = cmd.getEventId().getValue();
        StockReply reply;
        if (result instanceof ReserveStockResult.Reserved reserved) {
            requireExpiry(reserved.expiresAt(), orderId);
            List<StockReply.ReservedLine> lines = reserved.lines().stream()
                    .map(line -> {
                        requireExpiry(line.expiresAt(), orderId);
                        return new StockReply.ReservedLine(line.orderItemId().getValue(), line.sku().getValue(),
                                line.quantity(), line.expiresAt());
                    })
                    .toList();
            reply = new StockReply.Reserved(newEventId(), StockReply.Reserved.TYPE, now, merchantId, storeId,
                    orderId, causationId, reserved.expiresAt(), lines);
        } else if (result instanceof ReserveStockResult.Rejected rejected) {
            reply = new StockReply.Rejected(newEventId(), StockReply.Rejected.TYPE, now, merchantId, storeId,
                    orderId, causationId, rejected.orderReason() == null ? null : rejected.orderReason().name(),
                    rejected.lines().stream()
                            .map(line -> new StockReply.RejectedLine(line.orderItemId().getValue(),
                                    line.sku().getValue(), line.reason().name()))
                            .toList());
        } else {
            throw new IllegalStateException("Unknown reserve result " + result);
        }
        return List.of(OutboxMessage.of(reply));
    }

    public List<OutboxMessage> of(ConfirmStockReservationCmd cmd, ConfirmStockReservationResult result) {
        Instant now = clock.instant();
        UUID merchantId = cmd.getMerchantId().getValue();
        UUID storeId = cmd.getStoreId().getValue();
        UUID orderId = cmd.getOrderId().getValue();
        UUID causationId = cmd.getEventId().getValue();
        StockReply reply;
        if (result instanceof ConfirmStockReservationResult.Confirmed confirmed) {
            reply = new StockReply.Confirmed(newEventId(), StockReply.Confirmed.TYPE, now, merchantId, storeId,
                    orderId, causationId,
                    statusLines(confirmed.lines(), EnumSet.of(ReservationStatus.CONFIRMED), orderId));
        } else if (result instanceof ConfirmStockReservationResult.ConfirmationFailed failed) {
            reply = new StockReply.ConfirmationFailed(newEventId(), StockReply.ConfirmationFailed.TYPE, now,
                    merchantId, storeId, orderId, causationId, failed.reason().name(),
                    statusLines(failed.lines(), FAILED_STATUSES, orderId));
        } else {
            throw new IllegalStateException("Unknown confirm result " + result);
        }
        return List.of(OutboxMessage.of(reply));
    }

    public List<OutboxMessage> of(ReleaseStockReservationCmd cmd, ReleaseStockReservationResult result) {
        UUID orderId = cmd.getOrderId().getValue();
        List<StockReply.ReleasedLine> lines = result.lines().stream()
                .map(line -> {
                    requireStatus(line.status(), RELEASED_STATUSES, orderId);
                    return new StockReply.ReleasedLine(line.orderItemId().getValue(), line.status().name(),
                            line.restocked());
                })
                .toList();
        StockReply reply = new StockReply.Released(newEventId(), StockReply.Released.TYPE, clock.instant(),
                cmd.getMerchantId().getValue(), cmd.getStoreId().getValue(), orderId, cmd.getEventId().getValue(),
                result.restock(), lines);
        return List.of(OutboxMessage.of(reply));
    }

    /**
     * One {@code StockReservationExpired} per (order, merchant, store) among the reservations this transaction expired
     * (one inventory, so one SKU). Never throws.
     */
    public List<OutboxMessage> expired(List<StockReservation> winners, SKU sku) {
        Map<List<UUID>, List<StockReservation>> groups = new LinkedHashMap<>();
        for (StockReservation reservation : winners) {
            List<UUID> key = List.of(reservation.getOrderId().getValue(), reservation.getMerchantId().getValue(),
                    reservation.getStoreId().getValue());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(reservation);
        }
        Map<UUID, Integer> groupsPerOrder = new LinkedHashMap<>();
        groups.keySet().forEach(key -> groupsPerOrder.merge(key.get(0), 1, Integer::sum));
        groupsPerOrder.forEach((orderId, count) -> {
            if (count > 1) {
                log.warn("Expired reservations of order {} carry {} different merchant/store pairs", orderId, count);
            }
        });

        Instant now = clock.instant();
        List<OutboxMessage> messages = new ArrayList<>();
        groups.forEach((key, reservations) -> messages.add(OutboxMessage.of(new StockReply.Expired(newEventId(),
                StockReply.Expired.TYPE, now, key.get(1), key.get(2), key.get(0), null,
                reservations.stream()
                        .map(r -> new StockReply.ExpiredLine(r.getOrderItemId().getValue(), sku.getValue()))
                        .toList()))));
        return messages;
    }

    private static UUID newEventId() {
        return EventId.newEventId().getValue();
    }

    private static void requireExpiry(Instant expiresAt, UUID orderId) {
        if (expiresAt == null) {
            throw new IllegalStateException("Reservation of order " + orderId + " has no expires_at");
        }
    }

    private static List<StockReply.StatusLine> statusLines(List<ReservationLineStatus> lines,
                                                           Set<ReservationStatus> allowed, UUID orderId) {
        return lines.stream()
                .map(line -> {
                    requireStatus(line.status(), allowed, orderId);
                    return new StockReply.StatusLine(line.orderItemId().getValue(), line.status().name());
                })
                .toList();
    }

    private static void requireStatus(ReservationStatus status, Set<ReservationStatus> allowed, UUID orderId) {
        if (status == null || !allowed.contains(status)) {
            throw new IllegalStateException("Reservation status " + status + " of order " + orderId
                    + " cannot be published in this reply");
        }
    }
}
