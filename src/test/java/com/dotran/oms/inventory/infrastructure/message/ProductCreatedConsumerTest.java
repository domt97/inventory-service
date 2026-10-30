package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.SkuSnapshot;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.Source;
import com.dotran.oms.inventory.application.usecase.inventory.SyncProductInventoryUseCase;
import com.dotran.oms.inventory.domain.enums.ProductSkuStatus;
import com.dotran.oms.inventory.domain.enums.ProductStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCreatedConsumer")
class ProductCreatedConsumerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private SyncProductInventoryUseCase syncProductInventoryUseCase;

    private ProductCreatedConsumer consumer() {
        return new ProductCreatedConsumer(new OutboxPayloadDecoder(objectMapper), syncProductInventoryUseCase);
    }

    /** Kafka value as Debezium EventRouter publishes it: a JSON string containing the event JSON. */
    private ConsumerRecord<String, String> record(UUID productId, String eventJson) throws JsonProcessingException {
        return new ConsumerRecord<>("ProductCreated", 0, 0L, productId.toString(), objectMapper.writeValueAsString(eventJson));
    }

    private String event(UUID merchantId, UUID storeId, UUID productId, String skus, String extra) {
        return """
                {"eventId":"%s","eventType":"ProductCreated","occurredAt":"2026-10-25T10:00:00Z",
                 "merchantId":"%s","storeId":"%s","productId":"%s","skus":%s%s}
                """.formatted(UUID.randomUUID(), merchantId, storeId, productId, skus, extra);
    }

    @Test
    @DisplayName("maps the event to a version-0 sync with ACTIVE product and SKUs")
    void mapsToSyncCommand() throws Exception {
        UUID merchantId = UUID.randomUUID(), storeId = UUID.randomUUID(), productId = UUID.randomUUID();

        consumer().consume(record(productId, event(merchantId, storeId, productId, "[{\"value\":\"A\"},{\"value\":\"B\"}]", "")));

        ArgumentCaptor<SyncProductInventoryCmd> captor = ArgumentCaptor.forClass(SyncProductInventoryCmd.class);
        verify(syncProductInventoryUseCase).sync(captor.capture());
        SyncProductInventoryCmd cmd = captor.getValue();
        assertThat(cmd.getSource()).isEqualTo(Source.PRODUCT_CREATED);
        assertThat(cmd.getMerchantId()).isEqualTo(MerchantId.of(merchantId));
        assertThat(cmd.getStoreId()).isEqualTo(StoreId.of(storeId));
        assertThat(cmd.getProductId()).isEqualTo(ProductId.of(productId));
        assertThat(cmd.getVersion()).isZero();
        assertThat(cmd.getProductStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(cmd.getSkus()).containsExactly(
                new SkuSnapshot(SKU.of("A"), ProductSkuStatus.ACTIVE), new SkuSnapshot(SKU.of("B"), ProductSkuStatus.ACTIVE));
    }

    @Test
    @DisplayName("passes an explicit version through")
    void passesVersion() throws Exception {
        UUID productId = UUID.randomUUID();

        consumer().consume(record(productId, event(UUID.randomUUID(), UUID.randomUUID(), productId, "[{\"value\":\"A\"}]", ",\"version\":0")));

        ArgumentCaptor<SyncProductInventoryCmd> captor = ArgumentCaptor.forClass(SyncProductInventoryCmd.class);
        verify(syncProductInventoryUseCase).sync(captor.capture());
        assertThat(captor.getValue().getVersion()).isZero();
    }

    @Test
    @DisplayName("skips the use case when the event has no SKUs")
    void skipsWithoutSkus() throws Exception {
        UUID productId = UUID.randomUUID();

        consumer().consume(record(productId, event(UUID.randomUUID(), UUID.randomUUID(), productId, "[]", "")));

        verifyNoInteractions(syncProductInventoryUseCase);
    }

    @Test
    @DisplayName("ignores a tombstone (null value)")
    void ignoresTombstone() throws Exception {
        consumer().consume(new ConsumerRecord<>("ProductCreated", 0, 0L, "key", null));

        verifyNoInteractions(syncProductInventoryUseCase);
    }

    @Test
    @DisplayName("throws a JsonProcessingException for an unreadable payload and never calls the use case")
    void unreadablePayload_throws() {
        assertThatThrownBy(() -> consumer().consume(new ConsumerRecord<>("ProductCreated", 0, 0L, "key", "not json")))
                .isInstanceOf(JsonProcessingException.class);

        verifyNoInteractions(syncProductInventoryUseCase);
    }
}
