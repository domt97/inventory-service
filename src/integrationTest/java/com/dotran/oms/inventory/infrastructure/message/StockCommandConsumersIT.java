package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.ReplySchemas;
import com.dotran.oms.inventory.support.WalSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three stock command consumers on the real use cases and Postgres: a Kafka record (JSON as order-service would
 * send it, validated against the copied command schema) in, rows, movements and the reply in the WAL out. Called
 * directly, without a broker: the listener wiring, the error handler bean and the DLT are checked by hand.
 */
@Import({ReserveStockConsumer.class, ConfirmStockReservationConsumer.class, ReleaseStockReservationConsumer.class,
        OutboxPayloadDecoder.class})
@DisplayName("Stock command consumers end to end (Postgres, WAL)")
class StockCommandConsumersIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ReserveStockConsumer reserveConsumer;
    @Autowired
    private ConfirmStockReservationConsumer confirmConsumer;
    @Autowired
    private ReleaseStockReservationConsumer releaseConsumer;

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    private UUID skuA;
    private WalSlot wal;
    private long offset;

    @BeforeEach
    void setUp() {
        skuA = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        wal = WalSlot.create(jdbc, "it_stock_command_consumers");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
        assertThat(MDC.getCopyOfContextMap()).as("MDC after a consumer call").isNullOrEmpty();
    }

    private ObjectNode command(String type, UUID merchantId, UUID orderId) {
        return JSON.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", type)
                .put("occurredAt", Instant.now().toString())
                .put("merchantId", merchantId.toString())
                .put("storeId", store.toString())
                .put("orderId", orderId.toString());
    }

    private ObjectNode reserve(UUID orderId, long quantity) {
        ObjectNode command = command("ReserveStock", merchant, orderId);
        command.putArray("lines").add(line("SKU-A", quantity));
        return command;
    }

    private ObjectNode line(String sku, long quantity) {
        return JSON.createObjectNode().put("orderItemId", UUID.randomUUID().toString())
                .put("productId", product.toString()).put("sku", sku).put("quantity", quantity);
    }

    private ObjectNode confirm(UUID orderId) {
        return command("ConfirmStockReservation", merchant, orderId);
    }

    private ObjectNode release(UUID orderId, boolean restock) {
        return command("ReleaseStockReservation", merchant, orderId).put("restock", restock).put("reason", "CUSTOMER_CANCELLED");
    }

    /** The record of a command on the topic named in {@code topic}; a contract-valid payload is asserted to be one. */
    private ConsumerRecord<String, String> record(String topic, ObjectNode command, String requestId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, offset++,
                command.path("orderId").asText(null), command.toString());
        if (requestId != null) {
            record.headers().add(MdcPropagation.HEADER,
                    ("{\"requestId\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private ConsumerRecord<String, String> valid(ObjectNode command, String requestId) {
        assertThat(ReplySchemas.validate(command)).as(command.toString()).isEmpty();
        return record(command.get("eventType").asText(), command, requestId);
    }

    private void consume(ConsumerRecord<String, String> record) throws Exception {
        switch (record.topic()) {
            case "ReserveStock" -> reserveConsumer.consume(record);
            case "ConfirmStockReservation" -> confirmConsumer.consume(record);
            case "ReleaseStockReservation" -> releaseConsumer.consume(record);
            default -> throw new IllegalArgumentException(record.topic());
        }
    }

    /** One published reply: the outbox INSERT as the connector reads it from the WAL. */
    private record Reply(JsonNode payload, JsonNode correlation) {

        String type() {
            return payload.get("eventType").asText();
        }
    }

    /** Consumes the WAL and returns the replies published since the last call, each schema-valid. */
    private List<Reply> replies() {
        return wal.changes().stream().filter(c -> c.isOutbox() && c.op().equals("INSERT")).map(insert -> {
            try {
                JsonNode payload = JSON.readTree(insert.payload());
                assertThat(ReplySchemas.validate(payload)).as(payload.toString()).isEmpty();
                return new Reply(payload, JSON.readTree(insert.correlation()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
    }

    private Reply onlyReply() {
        List<Reply> replies = replies();
        assertThat(replies).hasSize(1);
        return replies.get(0);
    }

    private int movements(String type) {
        return count("SELECT count(*) FROM stock_movements WHERE type = ?", type);
    }

    private String header(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM stock_reservation_orders WHERE order_id = ?", String.class, orderId);
    }

    @Test
    @DisplayName("reserve → confirm → release with restock: stock, movements and one reply each, with causationId and the producer's requestId")
    void lifecycle() throws Exception {
        ObjectNode reserve = reserve(order, 3);
        consume(valid(reserve, "web-checkout-1"));

        Reply reserved = onlyReply();
        assertThat(reserved.type()).isEqualTo("StockReserved");
        assertThat(reserved.payload().get("causationId").asText()).isEqualTo(reserve.get("eventId").asText());
        assertThat(reserved.payload().get("orderId").asText()).isEqualTo(order.toString());
        assertThat(reserved.correlation().get("requestId").asText()).isEqualTo("web-checkout-1");
        assertThat(reserved(skuA)).isEqualTo(3);
        assertThat(movements("RESERVE")).isEqualTo(1);
        assertThat(header(order)).isEqualTo("RESERVED");

        ObjectNode confirm = confirm(order);
        consume(valid(confirm, "web-checkout-1"));

        Reply confirmed = onlyReply();
        assertThat(confirmed.type()).isEqualTo("StockReservationConfirmed");
        assertThat(confirmed.payload().get("causationId").asText()).isEqualTo(confirm.get("eventId").asText());
        assertThat(quantity(skuA)).isEqualTo(7);
        assertThat(reserved(skuA)).isZero();
        assertThat(movements("SALE")).isEqualTo(1);
        assertThat(header(order)).isEqualTo("CONFIRMED");

        ObjectNode release = release(order, true);
        // Without a correlationContext header the reply carries the record's coordinates as requestId.
        ConsumerRecord<String, String> releaseRecord = valid(release, null);
        consume(releaseRecord);

        Reply released = onlyReply();
        assertThat(released.type()).isEqualTo("StockReservationReleased");
        assertThat(released.payload().get("causationId").asText()).isEqualTo(release.get("eventId").asText());
        assertThat(released.payload().get("restock").asBoolean()).isTrue();
        assertThat(released.correlation().get("requestId").asText())
                .isEqualTo("ReleaseStockReservation-0-" + releaseRecord.offset());
        assertThat(quantity(skuA)).isEqualTo(10);
        assertThat(movements("RETURN")).isEqualTo(1);
        assertThat(header(order)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("the same record delivered twice: stock held once, a second reply with a new eventId and the same causationId")
    void redelivery() throws Exception {
        ObjectNode reserve = reserve(order, 4);
        ConsumerRecord<String, String> record = valid(reserve, "web-1");

        consume(record);
        Reply first = onlyReply();
        consume(record);
        Reply second = onlyReply();

        assertThat(second.type()).isEqualTo("StockReserved");
        assertThat(second.payload().get("eventId").asText()).isNotEqualTo(first.payload().get("eventId").asText());
        assertThat(second.payload().get("causationId").asText()).isEqualTo(reserve.get("eventId").asText());
        assertThat(reserved(skuA)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM stock_reservations WHERE order_id = ?", order)).isEqualTo(1);
        assertThat(movements("RESERVE")).isEqualTo(1);

        ConsumerRecord<String, String> releaseRecord = valid(release(order, false), null);
        consume(releaseRecord);
        consume(releaseRecord);

        assertThat(replies()).extracting(Reply::type)
                .containsExactly("StockReservationReleased", "StockReservationReleased");
        assertThat(reserved(skuA)).isZero();
        assertThat(movements("RESERVE_RELEASE")).isEqualTo(1);
    }

    @Test
    @DisplayName("business outcomes are replies, not errors: not enough stock, and a confirm after the release")
    void businessOutcomes() throws Exception {
        consume(valid(reserve(order, 11), null));

        Reply rejected = onlyReply();
        assertThat(rejected.type()).isEqualTo("StockReservationRejected");
        assertThat(rejected.payload().get("lines").get(0).get("reason").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(reserved(skuA)).isZero();

        UUID other = UUID.randomUUID();
        consume(valid(reserve(other, 2), null));
        consume(valid(release(other, false), null));
        consume(valid(confirm(other), null));

        List<Reply> replies = replies();
        assertThat(replies).extracting(Reply::type).containsExactly("StockReserved", "StockReservationReleased",
                "StockReservationConfirmationFailed");
        assertThat(replies.get(2).payload().get("reason").asText()).isEqualTo("ORDER_CLOSED");
        assertThat(quantity(skuA)).isEqualTo(10);
        assertThat(movements("SALE")).isZero();
    }

    @Test
    @DisplayName("an order of another merchant is a contract error: nothing written, nothing published")
    void otherMerchantsOrder() throws Exception {
        consume(valid(reserve(order, 2), null));
        replies();

        UUID stranger = UUID.randomUUID();
        for (ObjectNode command : List.of(command("ConfirmStockReservation", stranger, order),
                command("ReleaseStockReservation", stranger, order).put("restock", true).put("reason", "X"))) {
            assertThatThrownBy(() -> consume(valid(command, null))).as(command.get("eventType").asText())
                    .isInstanceOf(StockCommandOwnershipMismatchException.class);
        }

        assertThat(replies()).isEmpty();
        assertThat(header(order)).isEqualTo("RESERVED");
        assertThat(reserved(skuA)).isEqualTo(2);
        assertThat(quantity(skuA)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM stock_movements WHERE type <> 'RESERVE'")).isZero();
    }

    @Test
    @DisplayName("contract errors through the real validation: nothing written, nothing published")
    void contractErrors() {
        ObjectNode noOrder = reserve(order, 1);
        noOrder.remove("orderId");
        ObjectNode noEventId = confirm(order);
        noEventId.remove("eventId");
        ObjectNode noMerchant = release(order, true);
        noMerchant.putNull("merchantId");
        ObjectNode noOccurredAt = confirm(order);
        noOccurredAt.remove("occurredAt");
        ObjectNode noLines = reserve(order, 1);
        noLines.remove("lines");
        ObjectNode emptyLines = reserve(order, 1);
        emptyLines.putArray("lines");
        ObjectNode nullLine = reserve(order, 1);
        nullLine.putArray("lines").addNull();
        ObjectNode noSku = reserve(order, 1);
        ((ObjectNode) noSku.get("lines").get(0)).remove("sku");
        ObjectNode noQuantity = reserve(order, 1);
        ((ObjectNode) noQuantity.get("lines").get(0)).remove("quantity");
        ObjectNode fraction = reserve(order, 1);
        ((ObjectNode) fraction.get("lines").get(0)).put("quantity", 2.7);
        ObjectNode tooManyLines = reserve(order, 1);
        ArrayNode lines = tooManyLines.putArray("lines");
        for (int i = 0; i < 101; i++) {
            lines.add(line("SKU-A", 1));
        }
        ObjectNode noRestock = release(order, true);
        noRestock.remove("restock");
        ObjectNode restockAsNumber = release(order, true);
        restockAsNumber.put("restock", 1);
        ObjectNode longReason = release(order, true).put("reason", "R".repeat(51));
        // A release on the confirm topic: it would be read as a confirm without the eventType check.
        ConsumerRecord<String, String> wrongTopic = record("ConfirmStockReservation", release(order, true), null);

        for (ObjectNode command : List.of(noOrder, noEventId, noMerchant, noOccurredAt, noLines, emptyLines, nullLine,
                noSku, noQuantity, fraction, tooManyLines, noRestock, restockAsNumber, longReason)) {
            ConsumerRecord<String, String> record = record(command.get("eventType").asText(), command, null);
            assertThatThrownBy(() -> consume(record)).as(command.toString())
                    .isInstanceOf(InvalidStockCommandException.class);
        }
        assertThatThrownBy(() -> consume(wrongTopic)).isInstanceOf(InvalidStockCommandException.class);

        assertThat(replies()).isEmpty();
        assertThat(count("SELECT count(*) FROM stock_reservation_orders")).isZero();
        assertThat(count("SELECT count(*) FROM stock_reservations")).isZero();
        assertThat(count("SELECT count(*) FROM stock_movements")).isZero();
        assertThat(reserved(skuA)).isZero();
        assertThat(quantity(skuA)).isEqualTo(10);
    }
}
