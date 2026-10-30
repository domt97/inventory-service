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
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
import com.dotran.oms.inventory.infrastructure.message.dto.ProductUpdatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies a product's catalog snapshot (oms-workspace flows/product-updated.md). An empty SKU list is valid: every
 * inventory row of the product becomes INACTIVE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductUpdatedConsumer {

    private final OutboxPayloadDecoder payloadDecoder;
    private final SyncProductInventoryUseCase syncProductInventoryUseCase;

    @KafkaListener(
            topics = "ProductUpdated",
            groupId = "inventory-product-updated"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String aggregateId = record.key();

        if (record.value() == null) {
            log.warn("ProductUpdated event without a value (tombstone): aggregateId={}; ignored", aggregateId);
            return;
        }

        ProductUpdatedEvent event = payloadDecoder.decode(record.value(), ProductUpdatedEvent.class);
        log.info("Processing ProductUpdated event: eventId={}, productId={}, version={}",
                event.getEventId(), event.getProductId(), event.getVersion());

        syncProductInventoryUseCase.sync(toCmd(event));

        log.info("Finished processing ProductUpdated event: productId={}, version={}", event.getProductId(), event.getVersion());
    }

    private SyncProductInventoryCmd toCmd(ProductUpdatedEvent event) {
        if (event.getMerchantId() == null || event.getStoreId() == null || event.getProductId() == null
                || event.getSkus() == null) {
            throw new InvalidCatalogEventException("ProductUpdated event %s is missing a required field"
                    .formatted(event.getEventId()));
        }

        List<SkuSnapshot> skus = event.getSkus().stream()
                .map(sku -> {
                    if (sku == null || sku.getSku() == null) {
                        throw new InvalidCatalogEventException("ProductUpdated event %s has a SKU without a code"
                                .formatted(event.getEventId()));
                    }
                    return new SkuSnapshot(SKU.of(sku.getSku()), parse(ProductSkuStatus.class, sku.getStatus(), event));
                })
                .toList();

        return SyncProductInventoryCmd.builder()
                .source(Source.PRODUCT_UPDATED)
                .merchantId(MerchantId.of(event.getMerchantId()))
                .storeId(StoreId.of(event.getStoreId()))
                .productId(ProductId.of(event.getProductId()))
                .version(event.getVersion())
                .productStatus(parse(ProductStatus.class, event.getStatus(), event))
                .skus(skus)
                .build();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, ProductUpdatedEvent event) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidCatalogEventException("ProductUpdated event %s has an unknown %s '%s'"
                    .formatted(event.getEventId(), type.getSimpleName(), value));
        }
    }
}
