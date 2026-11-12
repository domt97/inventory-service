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

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test against the copied oms-workspace contracts/events/ConfirmStockReservation.schema.json: the fixture is validated against
 * the schema, then read. Every case runs with both mappers a consumer can get: Spring Boot's (unit tests, production)
 * and a plain ObjectMapper with only the JavaTime module (as in the integration tests), so the DTO itself must ignore
 * unknown fields. The lenient readings of Jackson are pinned here on purpose.
 */
@DisplayName("ConfirmStockReservationCommand JSON contract")
class ConfirmStockReservationCommandJsonTest {

    private static final String TYPE = "ConfirmStockReservation";
    private static final List<ObjectMapper> MAPPERS = List.of(
            Jackson2ObjectMapperBuilder.json().build(), new ObjectMapper().registerModule(new JavaTimeModule()));

    private final UUID eventId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private ObjectNode fixture() throws JsonProcessingException {
        return (ObjectNode) MAPPERS.get(0).readTree("""
                {"eventId":"%s","eventType":"ConfirmStockReservation","occurredAt":"2026-11-12T10:00:00.123Z",
                 "merchantId":"%s","storeId":"%s","orderId":"%s","futureField":{"x":1}}
                """.formatted(eventId, merchantId, storeId, orderId));
    }

    private static void assertContractValid(JsonNode payload) {
        assertThat(ContractSchemas.validate(TYPE, payload)).as(payload.toString()).isEmpty();
    }

    private final UUID merchantId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    @Test
    @DisplayName("reads the schema fields and ignores unknown ones")
    void readsSchemaFixture() throws Exception {
        assertContractValid(fixture());

        for (ObjectMapper mapper : MAPPERS) {
            ConfirmStockReservationCommand command = mapper.treeToValue(fixture(), ConfirmStockReservationCommand.class);

            assertThat(command.getEventId()).isEqualTo(eventId);
            assertThat(command.getEventType()).isEqualTo(TYPE);
            assertThat(command.getOccurredAt()).isEqualTo(Instant.parse("2026-11-12T10:00:00.123Z"));
            assertThat(command.getMerchantId()).isEqualTo(merchantId);
            assertThat(command.getStoreId()).isEqualTo(storeId);
            assertThat(command.getOrderId()).isEqualTo(orderId);
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
            ConfirmStockReservationCommand command = mapper.treeToValue(lenient, ConfirmStockReservationCommand.class);

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
            assertThatThrownBy(() -> mapper.treeToValue(fixture().put("orderId", "nope"), ConfirmStockReservationCommand.class))
                    .isInstanceOf(JsonProcessingException.class);
            assertThatThrownBy(() -> mapper.treeToValue(fixture().put("occurredAt", "soon"), ConfirmStockReservationCommand.class))
                    .isInstanceOf(JsonProcessingException.class);

            ConfirmStockReservationCommand command = mapper.treeToValue(missing, ConfirmStockReservationCommand.class);
            assertThat(command.getEventType()).isNull();
            assertThat(command.getEventId()).isNull();
            assertThat(command.getOccurredAt()).isNull();
            assertThat(command.getOrderId()).isEqualTo(orderId);
        }
    }
}
