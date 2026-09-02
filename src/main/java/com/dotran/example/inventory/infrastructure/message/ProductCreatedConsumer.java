package com.dotran.example.inventory.infrastructure.message;

import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.common.mapper.IdMapper;
import com.dotran.example.inventory.domain.event.ProductCreatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCreatedConsumer {

    private final ObjectMapper objectMapper;
    private final CreateInventoryUseCase createInventoryUseCase;
    private final IdMapper idMapper;

    @KafkaListener(
            topics = "ProductCreated",
            groupId = "product-consumer"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String aggregateId = record.key();
        log.info("Received ProductCreated event: aggregateId={}", aggregateId);

        String payload = record.value();
        String json = objectMapper.readValue(payload, String.class);

        ProductCreatedEvent event =
                objectMapper.readValue(json, ProductCreatedEvent.class);

        log.info("Processing ProductCreated event:" +
                " eventId = {}, occurredAt = {}", event.getEventId(), event.getOccurredAt());

        if (event.getSkus() == null || event.getSkus().isEmpty()) {
            log.warn("ProductCreated event has no SKUs: aggregateId={}", aggregateId);
            return;
        }

        TenantId tenantId = TenantId.of(event.getTenantId());
        StoreId storeId = StoreId.of(event.getStoreId());
        ProductId productId = ProductId.of(event.getProductId());

        CreateInventoryCmd createInventoryCmd = CreateInventoryCmd.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .productId(productId)
                .skus(event.getSkus())
                .build();

        createInventoryUseCase.create(createInventoryCmd);

        log.info("Finished processing ProductCreated event: aggregateId={}", aggregateId);
    }
}
