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
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductUpdatedConsumer")
class ProductUpdatedConsumerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private SyncProductInventoryUseCase syncProductInventoryUseCase;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private ProductUpdatedConsumer consumer() {
        return new ProductUpdatedConsumer(new OutboxPayloadDecoder(objectMapper), syncProductInventoryUseCase, TestMdc.propagation());
    }

    private ConsumerRecord<String, String> record(String status, String skus) throws JsonProcessingException {
        String json = """
                {"eventId":"%s","eventType":"ProductUpdated","occurredAt":"2026-10-28T10:00:00Z",
                 "merchantId":"%s","storeId":"%s","productId":"%s","version":5,"status":%s,"name":"P","skus":%s}
                """.formatted(UUID.randomUUID(), merchantId, storeId, productId, status, skus);
        return new ConsumerRecord<>("ProductUpdated", 0, 0L, productId.toString(), objectMapper.writeValueAsString(json));
    }

    @Test
    @DisplayName("maps the snapshot to a sync command")
    void mapsToSyncCommand() throws Exception {
        consumer().consume(record("\"INACTIVE\"",
                "[{\"id\":\"%s\",\"sku\":\"A\",\"status\":\"ACTIVE\",\"price\":1,\"currency\":\"VND\"},{\"sku\":\"B\",\"status\":\"INACTIVE\"}]"
                        .formatted(UUID.randomUUID())));

        ArgumentCaptor<SyncProductInventoryCmd> captor = ArgumentCaptor.forClass(SyncProductInventoryCmd.class);
        verify(syncProductInventoryUseCase).sync(captor.capture());
        SyncProductInventoryCmd cmd = captor.getValue();
        assertThat(cmd.getSource()).isEqualTo(Source.PRODUCT_UPDATED);
        assertThat(cmd.getMerchantId()).isEqualTo(MerchantId.of(merchantId));
        assertThat(cmd.getStoreId()).isEqualTo(StoreId.of(storeId));
        assertThat(cmd.getProductId()).isEqualTo(ProductId.of(productId));
        assertThat(cmd.getVersion()).isEqualTo(5L);
        assertThat(cmd.getProductStatus()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(cmd.getSkus()).containsExactly(
                new SkuSnapshot(SKU.of("A"), ProductSkuStatus.ACTIVE), new SkuSnapshot(SKU.of("B"), ProductSkuStatus.INACTIVE));
    }

    @Test
    @DisplayName("an empty SKU list still calls the use case (every row becomes INACTIVE)")
    void emptySkus_stillSyncs() throws Exception {
        consumer().consume(record("\"ACTIVE\"", "[]"));

        ArgumentCaptor<SyncProductInventoryCmd> captor = ArgumentCaptor.forClass(SyncProductInventoryCmd.class);
        verify(syncProductInventoryUseCase).sync(captor.capture());
        assertThat(captor.getValue().getSkus()).isEmpty();
    }

    @Test
    @DisplayName("an unknown or missing status is a contract error and never reaches the use case")
    void unknownStatus_isInvalid() {
        assertThatThrownBy(() -> consumer().consume(record("\"ARCHIVED\"", "[]"))).isInstanceOf(InvalidCatalogEventException.class);
        assertThatThrownBy(() -> consumer().consume(record("null", "[]"))).isInstanceOf(InvalidCatalogEventException.class);
        assertThatThrownBy(() -> consumer().consume(record("\"ACTIVE\"", "[{\"sku\":\"A\",\"status\":\"GONE\"}]")))
                .isInstanceOf(InvalidCatalogEventException.class);

        verifyNoInteractions(syncProductInventoryUseCase);
    }

    @Test
    @DisplayName("missing skus or a SKU without a code is a contract error")
    void missingFields_areInvalid() {
        assertThatThrownBy(() -> consumer().consume(record("\"ACTIVE\"", "null"))).isInstanceOf(InvalidCatalogEventException.class);
        assertThatThrownBy(() -> consumer().consume(record("\"ACTIVE\"", "[{\"status\":\"ACTIVE\"}]")))
                .isInstanceOf(InvalidCatalogEventException.class);

        verifyNoInteractions(syncProductInventoryUseCase);
    }

    @Test
    @DisplayName("ignores a tombstone (null value)")
    void ignoresTombstone() throws Exception {
        consumer().consume(new ConsumerRecord<>("ProductUpdated", 0, 0L, "key", null));

        verifyNoInteractions(syncProductInventoryUseCase);
    }

    @Test
    @DisplayName("MDC carries record coordinates and event ids while syncing; empty afterwards and after a contract error")
    void setsAndClearsMdc() throws Exception {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        doAnswer(inv -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(syncProductInventoryUseCase).sync(any());

        consumer().consume(record("\"ACTIVE\"", "[]"));

        assertThat(seen).containsEntry("requestId", "ProductUpdated-0-0")
                .containsEntry("merchantId", merchantId.toString())
                .containsEntry("storeId", storeId.toString())
                .containsEntry("productId", productId.toString())
                .containsKey("eventId");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        assertThatThrownBy(() -> consumer().consume(record("\"ARCHIVED\"", "[]"))).isInstanceOf(InvalidCatalogEventException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("correlationContext header sets requestId during sync; without it the record coordinates are used")
    void headerRequestId() throws Exception {
        java.util.List<String> seen = new java.util.ArrayList<>();
        doAnswer(inv -> {
            seen.add(MDC.get("requestId"));
            return null;
        }).when(syncProductInventoryUseCase).sync(any());

        ConsumerRecord<String, String> withHeader = record("\"ACTIVE\"", "[]");
        withHeader.headers().add(com.dotran.oms.core.logging.MdcPropagation.HEADER, TestMdc.header("{\"requestId\":\"web-upd\"}"));
        consumer().consume(withHeader);
        consumer().consume(record("\"ACTIVE\"", "[]"));

        assertThat(seen).containsExactly("web-upd", "ProductUpdated-0-0");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
