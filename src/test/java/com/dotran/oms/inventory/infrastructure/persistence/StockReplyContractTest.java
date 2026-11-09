package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.application.event.StockReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON written to the outbox (by {@link OutboxPayloadSerializer}) validates against the reply contracts copied
 * from oms-workspace (src/contractTest/resources/contracts/events, see its README).
 */
@DisplayName("Stock reply payloads vs contracts")
class StockReplyContractTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00.123456Z");
    private static final ObjectMapper READER = new ObjectMapper();

    private final OutboxPayloadSerializer serializer = new OutboxPayloadSerializer();
    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID causation = UUID.randomUUID();
    private final UUID item = UUID.randomUUID();

    private static Set<ValidationMessage> validate(String type, String json) throws IOException {
        try (InputStream in = StockReplyContractTest.class.getResourceAsStream(
                "/contracts/events/" + type + ".schema.json")) {
            assertThat(in).as("schema %s on the classpath", type).isNotNull();
            // 2020-12 treats "format" as an annotation unless format assertions are on.
            @SuppressWarnings("deprecation") // the setter works on every 1.x release; the builder is newer
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            config.setFormatAssertionsEnabled(true);
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
            return schema.validate(READER.readTree(json));
        }
    }

    private void assertValid(StockReply reply) throws IOException {
        assertThat(validate(reply.eventType(), serializer.toJson(reply))).as(reply.eventType()).isEmpty();
    }

    private StockReply.Rejected orderRejection(List<StockReply.RejectedLine> lines) {
        return new StockReply.Rejected(UUID.randomUUID(), StockReply.Rejected.TYPE, NOW, merchant, store, order,
                causation, "STALE_COMMAND", lines);
    }

    @Test
    @DisplayName("every reply variant validates")
    void allValid() throws IOException {
        assertValid(new StockReply.Reserved(UUID.randomUUID(), StockReply.Reserved.TYPE, NOW, merchant, store, order,
                causation, NOW, List.of(new StockReply.ReservedLine(item, "SKU-A", 2, NOW))));
        assertValid(orderRejection(List.of()));
        assertValid(new StockReply.Rejected(UUID.randomUUID(), StockReply.Rejected.TYPE, NOW, merchant, store, order,
                causation, null, List.of(new StockReply.RejectedLine(item, "SKU-A", "INSUFFICIENT_STOCK"))));
        assertValid(new StockReply.Confirmed(UUID.randomUUID(), StockReply.Confirmed.TYPE, NOW, merchant, store,
                order, causation, List.of(new StockReply.StatusLine(item, "CONFIRMED"))));
        assertValid(new StockReply.ConfirmationFailed(UUID.randomUUID(), StockReply.ConfirmationFailed.TYPE, NOW,
                merchant, store, order, causation, "UNKNOWN_ORDER", List.of()));
        assertValid(new StockReply.ConfirmationFailed(UUID.randomUUID(), StockReply.ConfirmationFailed.TYPE, NOW,
                merchant, store, order, causation, "NOT_RESERVED", List.of(new StockReply.StatusLine(item, "EXPIRED"))));
        assertValid(new StockReply.Released(UUID.randomUUID(), StockReply.Released.TYPE, NOW, merchant, store, order,
                causation, true, List.of(new StockReply.ReleasedLine(item, "RETURNED", true))));
        assertValid(new StockReply.Released(UUID.randomUUID(), StockReply.Released.TYPE, NOW, merchant, store, order,
                causation, false, List.of()));
        assertValid(new StockReply.Expired(UUID.randomUUID(), StockReply.Expired.TYPE, NOW, merchant, store, order,
                null, List.of(new StockReply.ExpiredLine(item, "SKU-A"))));
    }

    @Test
    @DisplayName("nulls are written, ids and instants are strings")
    void shape() throws IOException {
        JsonNode json = READER.readTree(serializer.toJson(new StockReply.Expired(UUID.randomUUID(),
                StockReply.Expired.TYPE, NOW, merchant, store, order, null,
                List.of(new StockReply.ExpiredLine(item, "SKU-A")))));

        assertThat(json.has("causationId")).isTrue();
        assertThat(json.get("causationId").isNull()).isTrue();
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-11-09T10:00:00.123456Z");
        assertThat(json.get("orderId").asText()).isEqualTo(order.toString());
        assertThat(json.get("lines").get(0).get("sku").isTextual()).isTrue();
    }

    @Test
    @DisplayName("negative: an order-level rejection with lines, an Expired with a causationId, a typed-id sku object")
    void invalidPayloadsAreCaught() throws IOException {
        String rejectedWithLines = serializer.toJson(orderRejection(
                List.of(new StockReply.RejectedLine(item, "SKU-A", "UNKNOWN_SKU"))));
        assertThat(validate("StockReservationRejected", rejectedWithLines)).isNotEmpty();

        String expiredWithCausation = serializer.toJson(new StockReply.Expired(UUID.randomUUID(),
                StockReply.Expired.TYPE, NOW, merchant, store, order, causation,
                List.of(new StockReply.ExpiredLine(item, "SKU-A"))));
        assertThat(validate("StockReservationExpired", expiredWithCausation)).isNotEmpty();

        ObjectNode reserved = (ObjectNode) READER.readTree(serializer.toJson(new StockReply.Reserved(UUID.randomUUID(),
                StockReply.Reserved.TYPE, NOW, merchant, store, order, causation, NOW,
                List.of(new StockReply.ReservedLine(item, "SKU-A", 2, NOW)))));
        ((ObjectNode) reserved.get("lines").get(0)).putObject("sku").put("value", "SKU-A");
        assertThat(validate("StockReserved", reserved.toString())).isNotEmpty();
    }
}
