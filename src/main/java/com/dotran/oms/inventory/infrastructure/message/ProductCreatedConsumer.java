package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.SkuSnapshot;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.Source;
import com.dotran.oms.inventory.application.usecase.inventory.SyncProductInventoryUseCase;
import com.dotran.oms.inventory.domain.enums.ProductSkuStatus;
import com.dotran.oms.inventory.domain.enums.ProductStatus;
import com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Creates inventory for a new product. Goes through the catalog version gate as version 0, so a ProductCreated that
 * arrives after a ProductUpdated of the same product is a no-op. stores-service creates products and SKUs ACTIVE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCreatedConsumer {

    private final OutboxPayloadDecoder payloadDecoder;
    private final SyncProductInventoryUseCase syncProductInventoryUseCase;

    @KafkaListener(
            topics = "ProductCreated",
            groupId = "product-consumer"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String aggregateId = record.key();
        log.info("Received ProductCreated event: aggregateId={}", aggregateId);

        if (record.value() == null) {
            log.warn("ProductCreated event without a value (tombstone): aggregateId={}; ignored", aggregateId);
            return;
        }

        ProductCreatedEvent event = payloadDecoder.decode(record.value(), ProductCreatedEvent.class);

        log.info("Processing ProductCreated event:" +
                " eventId = {}, occurredAt = {}", event.getEventId(), event.getOccurredAt());

        if (event.getSkus() == null || event.getSkus().isEmpty()) {
            log.warn("ProductCreated event has no SKUs: aggregateId={}", aggregateId);
            return;
        }

        SyncProductInventoryCmd cmd = SyncProductInventoryCmd.builder()
                .source(Source.PRODUCT_CREATED)
                .merchantId(event.getMerchantId() != null ? MerchantId.of(event.getMerchantId()) : null)
                .storeId(event.getStoreId() != null ? StoreId.of(event.getStoreId()) : null)
                .productId(event.getProductId() != null ? ProductId.of(event.getProductId()) : null)
                .version(event.getVersion() != null ? event.getVersion() : 0L)
                .productStatus(ProductStatus.ACTIVE)
                .skus(event.getSkus().stream()
                        .map(sku -> new SkuSnapshot(sku, ProductSkuStatus.ACTIVE))
                        .toList())
                .build();

        syncProductInventoryUseCase.sync(cmd);

        log.info("Finished processing ProductCreated event: aggregateId={}", aggregateId);
    }
}
