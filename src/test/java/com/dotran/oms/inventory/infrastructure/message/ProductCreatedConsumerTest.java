package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.support.TestMdc;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCreatedConsumer")
class ProductCreatedConsumerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private SyncProductInventoryUseCase syncProductInventoryUseCase;

    private ProductCreatedConsumer consumer() {
        return new ProductCreatedConsumer(new OutboxPayloadDecoder(objectMapper), syncProductInventoryUseCase, TestMdc.propagation());
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

    @Test
    @DisplayName("MDC carries record coordinates and event ids while syncing, and is empty afterwards")
    void setsMdcWhileSyncing() throws Exception {
        UUID merchantId = UUID.randomUUID(), storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        doAnswer(inv -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(syncProductInventoryUseCase).sync(any());

        consumer().consume(record(productId, event(merchantId, storeId, productId, "[{\"value\":\"A\"}]", "")));

        assertThat(seen).containsEntry("requestId", "ProductCreated-0-0")
                .containsEntry("kafkaTopic", "ProductCreated")
                .containsEntry("kafkaPartition", "0")
                .containsEntry("kafkaOffset", "0")
                .containsEntry("merchantId", merchantId.toString())
                .containsEntry("storeId", storeId.toString())
                .containsEntry("productId", productId.toString())
                .containsKey("eventId");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("MDC is empty after the sync fails and after an unreadable payload")
    void clearsMdcOnFailure() throws Exception {
        UUID productId = UUID.randomUUID();
        doThrow(new IllegalStateException("db down")).when(syncProductInventoryUseCase).sync(any());

        assertThatThrownBy(() -> consumer().consume(record(productId,
                event(UUID.randomUUID(), UUID.randomUUID(), productId, "[{\"value\":\"A\"}]", ""))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        assertThatThrownBy(() -> consumer().consume(new ConsumerRecord<>("ProductCreated", 0, 1L, "key", "\"not json")))
                .isInstanceOf(Exception.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("correlationContext header: requestId is the producer's during sync; a header merchantId is ignored; a broken header is still processed")
    void headerRequestId() throws Exception {
        UUID merchantId = UUID.randomUUID(), storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        java.util.List<java.util.Map<String, String>> seen = new java.util.ArrayList<>();
        doAnswer(inv -> {
            seen.add(MDC.getCopyOfContextMap());
            return null;
        }).when(syncProductInventoryUseCase).sync(any());

        ConsumerRecord<String, String> withHeader = record(productId, event(merchantId, storeId, productId, "[{\"value\":\"A\"}]", ""));
        withHeader.headers().add(com.dotran.oms.core.logging.MdcPropagation.HEADER,
                TestMdc.header("{\"requestId\":\"web-abc\",\"merchantId\":\"" + UUID.randomUUID() + "\"}"));
        consumer().consume(withHeader);

        ConsumerRecord<String, String> broken = record(productId, event(merchantId, storeId, productId, "[{\"value\":\"A\"}]", ""));
        broken.headers().add(com.dotran.oms.core.logging.MdcPropagation.HEADER, TestMdc.header("{not json"));
        consumer().consume(broken);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).containsEntry("requestId", "web-abc").containsEntry("merchantId", merchantId.toString());
        assertThat(seen.get(1)).containsEntry("requestId", "ProductCreated-0-0");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("an undecodable payload with a valid header: requestId from the header while failing, nothing left after")
    void headerWithBrokenPayload() {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        OutboxPayloadDecoder decoder = new OutboxPayloadDecoder(objectMapper) {
            @Override
            public <T> T decode(String value, Class<T> type) throws JsonProcessingException {
                seen.set(MDC.get("requestId"));
                return super.decode(value, type);
            }
        };
        ConsumerRecord<String, String> record = new ConsumerRecord<>("ProductCreated", 0, 3L, "key", "\"not json");
        record.headers().add(com.dotran.oms.core.logging.MdcPropagation.HEADER, TestMdc.header("{\"requestId\":\"web-abc\"}"));

        assertThatThrownBy(() -> new ProductCreatedConsumer(decoder, syncProductInventoryUseCase, TestMdc.propagation()).consume(record))
                .isInstanceOf(Exception.class);

        assertThat(seen.get()).isEqualTo("web-abc");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
