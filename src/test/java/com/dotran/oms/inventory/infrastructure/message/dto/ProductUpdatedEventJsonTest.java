package com.dotran.oms.inventory.infrastructure.message.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test against oms-workspace contracts/events/ProductUpdated.schema.json. Uses a plain ObjectMapper with
 * only the JavaTime module so the DTO itself must ignore unknown fields.
 */
@DisplayName("ProductUpdatedEvent JSON contract")
class ProductUpdatedEventJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("reads the schema fields, plain-string SKU codes, null categoryId and ignores unknown fields")
    void readsSchemaFixture() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        String json = """
                {"eventId":"%s","eventType":"ProductUpdated","occurredAt":"2026-10-28T10:00:00Z",
                 "merchantId":"%s","storeId":"%s","productId":"%s","version":4,"status":"ACTIVE",
                 "name":"Milk Tea","categoryId":null,"futureField":{"x":1},
                 "skus":[{"id":"%s","sku":"MT-L","name":"Large","status":"INACTIVE","price":45000,"currency":"VND","extra":true}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), productId, skuId);

        ProductUpdatedEvent event = objectMapper.readValue(json, ProductUpdatedEvent.class);

        assertThat(event.getProductId()).isEqualTo(productId);
        assertThat(event.getVersion()).isEqualTo(4L);
        assertThat(event.getStatus()).isEqualTo("ACTIVE");
        assertThat(event.getCategoryId()).isNull();
        assertThat(event.getSkus()).singleElement().satisfies(sku -> {
            assertThat(sku.getId()).isEqualTo(skuId);
            assertThat(sku.getSku()).isEqualTo("MT-L");
            assertThat(sku.getStatus()).isEqualTo("INACTIVE");
            assertThat(sku.getPrice()).isEqualByComparingTo(new BigDecimal("45000"));
        });
    }
}
