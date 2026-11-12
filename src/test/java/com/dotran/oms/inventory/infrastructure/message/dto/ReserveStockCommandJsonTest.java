package com.dotran.oms.inventory.infrastructure.message.dto;

import com.dotran.oms.inventory.support.ContractSchemas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test against the copied oms-workspace contracts/events/ReserveStock.schema.json: the fixture is validated against
 * the schema, then read. Every case runs with both mappers a consumer can get: Spring Boot's (unit tests, production)
 * and a plain ObjectMapper with only the JavaTime module (as in the integration tests), so the DTO itself must ignore
 * unknown fields. The lenient readings of Jackson are pinned here on purpose.
 */
@DisplayName("ReserveStockCommand JSON contract")
class ReserveStockCommandJsonTest {

    private static final String TYPE = "ReserveStock";
    private static final List<ObjectMapper> MAPPERS = List.of(
            Jackson2ObjectMapperBuilder.json().build(), new ObjectMapper().registerModule(new JavaTimeModule()));

    private final UUID eventId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private ObjectNode fixture() throws JsonProcessingException {
        return (ObjectNode) MAPPERS.get(0).readTree("""
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-11-12T10:00:00.123Z",
                 "merchantId":"%s","storeId":"%s","orderId":"%s","futureField":{"x":1},
                 "lines":[{"orderItemId":"%s","productId":"%s","sku":"MT-L","quantity":2,"extra":true}]}
                """.formatted(eventId, merchantId, storeId, orderId, orderItemId, productId));
    }

    private static void assertContractValid(JsonNode payload) {
        assertThat(ContractSchemas.validate(TYPE, payload)).as(payload.toString()).isEmpty();
    }

    private final UUID merchantId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID orderItemId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    @DisplayName("reads the schema fields and ignores unknown ones, also inside a line")
    void readsSchemaFixture() throws Exception {
        assertContractValid(fixture());

        for (ObjectMapper mapper : MAPPERS) {
            ReserveStockCommand command = mapper.treeToValue(fixture(), ReserveStockCommand.class);

            assertThat(command.getEventId()).isEqualTo(eventId);
            assertThat(command.getEventType()).isEqualTo(TYPE);
            assertThat(command.getOccurredAt()).isEqualTo(Instant.parse("2026-11-12T10:00:00.123Z"));
            assertThat(command.getMerchantId()).isEqualTo(merchantId);
            assertThat(command.getStoreId()).isEqualTo(storeId);
            assertThat(command.getOrderId()).isEqualTo(orderId);
            assertThat(command.getLines()).singleElement().satisfies(line -> {
                assertThat(line.getOrderItemId()).isEqualTo(orderItemId);
                assertThat(line.getProductId()).isEqualTo(productId);
                assertThat(line.getSku()).isEqualTo("MT-L");
                assertThat(line.getQuantity()).isEqualByComparingTo("2");
            });
        }
    }

    @Test
    @DisplayName("lenient readings, pinned: occurredAt as a number is epoch seconds, a 24-character base64 UUID is decoded")
    void lenientReadings() throws Exception {
        byte[] bytes = ByteBuffer.allocate(16).putLong(orderId.getMostSignificantBits())
                .putLong(orderId.getLeastSignificantBits()).array();
        ObjectNode lenient = fixture().put("occurredAt", 1_762_941_600L)
                .put("orderId", Base64.getEncoder().encodeToString(bytes));

        for (ObjectMapper mapper : MAPPERS) {
            ReserveStockCommand command = mapper.treeToValue(lenient, ReserveStockCommand.class);

            // 2025-11-12T10:00:00Z in seconds; read as milliseconds it would be in January 1970.
            assertThat(command.getOccurredAt()).isEqualTo(Instant.parse("2025-11-12T10:00:00Z"));
            assertThat(command.getOrderId()).isEqualTo(orderId);
        }
    }

    @Test
    @DisplayName("a malformed UUID or instant is a JSON error; a missing field is null (eventType has no default)")
    void malformedAndMissing() throws Exception {
        ObjectNode missing = fixture();
        missing.remove(List.of("eventType", "eventId", "occurredAt"));

        for (ObjectMapper mapper : MAPPERS) {
            assertThatThrownBy(() -> mapper.treeToValue(fixture().put("orderId", "nope"), ReserveStockCommand.class))
                    .isInstanceOf(JsonProcessingException.class);
            assertThatThrownBy(() -> mapper.treeToValue(fixture().put("occurredAt", "soon"), ReserveStockCommand.class))
                    .isInstanceOf(JsonProcessingException.class);

            ReserveStockCommand command = mapper.treeToValue(missing, ReserveStockCommand.class);
            assertThat(command.getEventType()).isNull();
            assertThat(command.getEventId()).isNull();
            assertThat(command.getOccurredAt()).isNull();
            assertThat(command.getOrderId()).isEqualTo(orderId);
        }
    }

    @Test
    @DisplayName("quantity keeps its fraction (2.7 is not truncated to 2); the string \"5\" is read as 5; a missing one is null")
    void quantityIsNotTruncated() throws Exception {
        for (ObjectMapper mapper : MAPPERS) {
            ObjectNode fraction = fixture();
            ((ObjectNode) fraction.get("lines").get(0)).put("quantity", 2.7);
            assertThat(mapper.treeToValue(fraction, ReserveStockCommand.class).getLines().get(0).getQuantity())
                    .isEqualByComparingTo(new BigDecimal("2.7"));

            ObjectNode text = fixture();
            ((ObjectNode) text.get("lines").get(0)).put("quantity", "5");
            assertThat(mapper.treeToValue(text, ReserveStockCommand.class).getLines().get(0).getQuantity())
                    .isEqualByComparingTo("5");

            ObjectNode none = fixture();
            ((ObjectNode) none.get("lines").get(0)).remove("quantity");
            assertThat(mapper.treeToValue(none, ReserveStockCommand.class).getLines().get(0).getQuantity()).isNull();
        }
    }
}
