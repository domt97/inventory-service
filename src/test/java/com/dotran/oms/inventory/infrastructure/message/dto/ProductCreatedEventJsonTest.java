package com.dotran.oms.inventory.infrastructure.message.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the consumed ProductCreated payload, with a real ObjectMapper close to Spring Boot's (unknown properties ignored, ISO dates).
 */
@DisplayName("ProductCreatedEvent JSON contract")
class ProductCreatedEventJsonTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @DisplayName("reads merchantId from the payload")
    void readsMerchantId() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String json = """
                {"eventId":"%s","eventType":"ProductCreated","occurredAt":"2026-10-25T10:00:00Z",
                 "merchantId":"%s","storeId":"%s","productId":"%s"}
                """.formatted(UUID.randomUUID(), merchantId, storeId, UUID.randomUUID());

        ProductCreatedEvent event = objectMapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.getMerchantId()).isEqualTo(merchantId);
        assertThat(event.getStoreId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("a pre-rename payload with only tenantId has no merchantId (old messages must not be replayed)")
    void oldTenantIdPayloadHasNoMerchantId() throws Exception {
        String json = """
                {"eventId":"%s","eventType":"ProductCreated","occurredAt":"2026-10-25T10:00:00Z",
                 "tenantId":"%s","storeId":"%s","productId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        ProductCreatedEvent event = objectMapper.readValue(json, ProductCreatedEvent.class);

        assertThat(event.getMerchantId()).isNull();
    }

    @Test
    @DisplayName("reads the optional version; a payload without it has a null version (treated as 0)")
    void readsOptionalVersion() throws Exception {
        String base = """
                {"eventId":"%s","eventType":"ProductCreated","occurredAt":"2026-10-25T10:00:00Z",
                 "merchantId":"%s","storeId":"%s","productId":"%s","skus":[{"value":"SKU-1"}]%s}
                """;
        String withVersion = base.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ",\"version\":0");
        String withoutVersion = base.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "");

        assertThat(objectMapper.readValue(withVersion, ProductCreatedEvent.class).getVersion()).isZero();
        assertThat(objectMapper.readValue(withoutVersion, ProductCreatedEvent.class).getVersion()).isNull();
    }
}
