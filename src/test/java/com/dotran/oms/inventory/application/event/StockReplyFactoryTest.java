package com.dotran.oms.inventory.application.event;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReservationLineStatus;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockReplyFactory")
class StockReplyFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-09T10:15:00Z");

    private final StockReplyFactory factory = new StockReplyFactory(Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID item1 = UUID.randomUUID();
    private final UUID item2 = UUID.randomUUID();
    private final EventId commandId = EventId.newEventId();

    private ReserveStockCmd reserveCmd() {
        return ReserveStockCmd.builder().eventId(commandId).occurredAt(NOW).merchantId(MerchantId.of(merchant))
                .storeId(StoreId.of(store)).orderId(OrderId.of(order)).lines(List.of()).build();
    }

    private ConfirmStockReservationCmd confirmCmd() {
        return ConfirmStockReservationCmd.builder().eventId(commandId).occurredAt(NOW)
                .merchantId(MerchantId.of(merchant)).storeId(StoreId.of(store)).orderId(OrderId.of(order)).build();
    }

    private ReleaseStockReservationCmd releaseCmd() {
        return ReleaseStockReservationCmd.builder().eventId(commandId).occurredAt(NOW)
                .merchantId(MerchantId.of(merchant)).storeId(StoreId.of(store)).orderId(OrderId.of(order))
                .restock(true).reason("TEST").build();
    }

    private static <T> T single(List<OutboxMessage> messages, Class<T> type) {
        assertThat(messages).hasSize(1);
        return type.cast(messages.get(0).payload());
    }

    private void assertEnvelope(OutboxMessage message, String type) {
        assertThat(message.aggregateType()).isEqualTo("StockReservation");
        assertThat(message.aggregateId()).isEqualTo(order);
        assertThat(message.eventType()).isEqualTo(type);
        assertThat(message.merchantId()).isEqualTo(merchant);
        assertThat(message.eventId()).isEqualTo(message.payload().eventId());
        StockReply reply = message.payload();
        assertThat(reply.eventType()).isEqualTo(type);
        assertThat(reply.occurredAt()).isEqualTo(NOW);
        assertThat(reply.merchantId()).isEqualTo(merchant);
        assertThat(reply.storeId()).isEqualTo(store);
        assertThat(reply.orderId()).isEqualTo(order);
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {

        @Test
        @DisplayName("Reserved: envelope, causationId = command eventId, lines with plain sku")
        void reserved() {
            List<OutboxMessage> messages = factory.of(reserveCmd(), new ReserveStockResult.Reserved(EXPIRES, List.of(
                    new ReserveStockResult.Line(OrderItemId.of(item1), SKU.of("SKU-A"), 2, EXPIRES))));

            assertEnvelope(messages.get(0), "StockReserved");
            StockReply.Reserved reply = single(messages, StockReply.Reserved.class);
            assertThat(reply.causationId()).isEqualTo(commandId.getValue());
            assertThat(reply.expiresAt()).isEqualTo(EXPIRES);
            assertThat(reply.lines()).containsExactly(new StockReply.ReservedLine(item1, "SKU-A", 2, EXPIRES));
        }

        @Test
        @DisplayName("a new eventId on every call (a duplicate gets a new reply, BR-INV-07)")
        void newEventIdEachCall() {
            ReserveStockResult result = new ReserveStockResult.Rejected(OrderRejectionReason.ORDER_CLOSED, List.of());

            UUID first = factory.of(reserveCmd(), result).get(0).eventId();
            UUID second = factory.of(reserveCmd(), result).get(0).eventId();

            assertThat(first).isNotEqualTo(second).isNotEqualTo(commandId.getValue());
        }

        @Test
        @DisplayName("Rejected for the whole order: orderReason set, lines empty")
        void rejectedOrder() {
            StockReply.Rejected reply = single(factory.of(reserveCmd(),
                    new ReserveStockResult.Rejected(OrderRejectionReason.STALE_COMMAND, List.of())),
                    StockReply.Rejected.class);

            assertThat(reply.orderReason()).isEqualTo("STALE_COMMAND");
            assertThat(reply.lines()).isEmpty();
        }

        @Test
        @DisplayName("Rejected lines: orderReason null, reason per line")
        void rejectedLines() {
            StockReply.Rejected reply = single(factory.of(reserveCmd(), ReserveStockResult.Rejected.of(
                    ReservationRejection.ofLines(List.of(new ReservationRejection.Line(OrderItemId.of(item1),
                            SKU.of("SKU-A"), LineRejectionReason.UNKNOWN_SKU))))), StockReply.Rejected.class);

            assertThat(reply.orderReason()).isNull();
            assertThat(reply.lines()).containsExactly(new StockReply.RejectedLine(item1, "SKU-A", "UNKNOWN_SKU"));
        }

        @Test
        @DisplayName("a missing expiresAt (top level or line) is an internal error (D3)")
        void missingExpiry() {
            assertThatThrownBy(() -> factory.of(reserveCmd(), new ReserveStockResult.Reserved(null, List.of(
                    new ReserveStockResult.Line(OrderItemId.of(item1), SKU.of("SKU-A"), 2, EXPIRES)))))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> factory.of(reserveCmd(), new ReserveStockResult.Reserved(EXPIRES, List.of(
                    new ReserveStockResult.Line(OrderItemId.of(item1), SKU.of("SKU-A"), 2, null)))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("confirm")
    class Confirm {

        @Test
        @DisplayName("Confirmed lines carry status CONFIRMED")
        void confirmed() {
            List<OutboxMessage> messages = factory.of(confirmCmd(), new ConfirmStockReservationResult.Confirmed(
                    List.of(new ReservationLineStatus(OrderItemId.of(item1), ReservationStatus.CONFIRMED))));

            assertEnvelope(messages.get(0), "StockReservationConfirmed");
            assertThat(single(messages, StockReply.Confirmed.class).lines())
                    .containsExactly(new StockReply.StatusLine(item1, "CONFIRMED"));
        }

        @Test
        @DisplayName("a Confirmed line that is not CONFIRMED is an internal error")
        void confirmedWrongStatus() {
            assertThatThrownBy(() -> factory.of(confirmCmd(), new ConfirmStockReservationResult.Confirmed(
                    List.of(new ReservationLineStatus(OrderItemId.of(item1), ReservationStatus.RESERVED)))))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("UNKNOWN_ORDER (tombstone) has no lines; CANCELLED is refused")
        void failed() {
            StockReply.ConfirmationFailed reply = single(factory.of(confirmCmd(),
                    new ConfirmStockReservationResult.ConfirmationFailed(
                            ConfirmStockReservationResult.Reason.UNKNOWN_ORDER, List.of())),
                    StockReply.ConfirmationFailed.class);

            assertThat(reply.reason()).isEqualTo("UNKNOWN_ORDER");
            assertThat(reply.lines()).isEmpty();
            assertThatThrownBy(() -> factory.of(confirmCmd(), new ConfirmStockReservationResult.ConfirmationFailed(
                    ConfirmStockReservationResult.Reason.NOT_RESERVED,
                    List.of(new ReservationLineStatus(OrderItemId.of(item1), ReservationStatus.CANCELLED)))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("Released: restock and lines; no lines on a REJECTED header")
        void released() {
            List<OutboxMessage> messages = factory.of(releaseCmd(), new ReleaseStockReservationResult(true, List.of(
                    new ReleaseStockReservationResult.Line(OrderItemId.of(item1), ReservationStatus.RETURNED, true))));

            assertEnvelope(messages.get(0), "StockReservationReleased");
            StockReply.Released reply = single(messages, StockReply.Released.class);
            assertThat(reply.restock()).isTrue();
            assertThat(reply.lines()).containsExactly(new StockReply.ReleasedLine(item1, "RETURNED", true));
            assertThat(single(factory.of(releaseCmd(), new ReleaseStockReservationResult(false, List.of())),
                    StockReply.Released.class).lines()).isEmpty();
        }

        @Test
        @DisplayName("RESERVED or CANCELLED in a Released reply is an internal error")
        void releasedWrongStatus() {
            for (ReservationStatus status : new ReservationStatus[]{ReservationStatus.RESERVED,
                    ReservationStatus.CANCELLED}) {
                assertThatThrownBy(() -> factory.of(releaseCmd(), new ReleaseStockReservationResult(false, List.of(
                        new ReleaseStockReservationResult.Line(OrderItemId.of(item1), status, false)))))
                        .as(status.name())
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        private StockReservation reservation(long id, UUID orderId, UUID merchantId, UUID orderItemId) {
            return StockReservation.builder()
                    .id(StockReservationId.of(id)).merchantId(MerchantId.of(merchantId)).storeId(StoreId.of(store))
                    .inventoryId(InventoryId.of(UUID.randomUUID())).orderId(OrderId.of(orderId))
                    .orderItemId(OrderItemId.of(orderItemId)).quantity(1L).status(ReservationStatus.EXPIRED)
                    .expiresAt(NOW).createdAt(NOW).updatedAt(NOW).build();
        }

        @Test
        @DisplayName("one event per order, lines in reservation order, causationId null, sku of the inventory")
        void groupsByOrder() {
            UUID otherOrder = UUID.randomUUID();
            List<OutboxMessage> messages = factory.expired(List.of(
                    reservation(1, order, merchant, item1),
                    reservation(2, otherOrder, merchant, UUID.randomUUID()),
                    reservation(3, order, merchant, item2)), SKU.of("SKU-A"));

            assertThat(messages).hasSize(2);
            assertEnvelope(messages.get(0), "StockReservationExpired");
            StockReply.Expired first = (StockReply.Expired) messages.get(0).payload();
            assertThat(first.causationId()).isNull();
            assertThat(first.lines()).containsExactly(new StockReply.ExpiredLine(item1, "SKU-A"),
                    new StockReply.ExpiredLine(item2, "SKU-A"));
            assertThat(messages.get(1).aggregateId()).isEqualTo(otherOrder);
            assertThat(messages.get(0).eventId()).isNotEqualTo(messages.get(1).eventId());
        }

        @Test
        @DisplayName("one order with rows of two merchants gives two events and never throws")
        void mixedMerchantNeverThrows() {
            UUID otherMerchant = UUID.randomUUID();

            List<OutboxMessage> messages = factory.expired(List.of(reservation(1, order, merchant, item1),
                    reservation(2, order, otherMerchant, item2)), SKU.of("SKU-A"));

            assertThat(messages).extracting(OutboxMessage::merchantId).containsExactly(merchant, otherMerchant);
        }

        @Test
        @DisplayName("no winners, no events")
        void empty() {
            assertThat(factory.expired(List.of(), SKU.of("SKU-A"))).isEmpty();
        }
    }
}
