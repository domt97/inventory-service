package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.command.CreateInventoryCmd;
import com.dotran.oms.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.oms.core.domain.id.SKU;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCreatedConsumer")
class ProductCreatedConsumerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CreateInventoryUseCase createInventoryUseCase;

    @InjectMocks
    private ProductCreatedConsumer consumer;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String PAYLOAD = "\"raw-json\"";
    private static final String JSON = "event-json";

    @BeforeEach
    void setUp() throws JsonProcessingException {
        when(objectMapper.readValue(PAYLOAD, String.class)).thenReturn(JSON);
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("ProductCreated", 0, 0, key, value);
    }

    @Test
    @DisplayName("skips use case when event has no SKUs")
    void shouldSkipWhenSkusNull() throws JsonProcessingException {
        var event = com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent.builder()
                .eventId(UUID.randomUUID()).occurredAt(Instant.now())
                .tenantId(TENANT_ID).storeId(STORE_ID).productId(PRODUCT_ID)
                .skus(null)
                .build();

        when(objectMapper.readValue(eq(JSON), eq(com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent.class)))
                .thenReturn(event);

        consumer.consume(record("key-1", PAYLOAD));

        verifyNoInteractions(createInventoryUseCase);
    }

    @Test
    @DisplayName("skips use case when event has empty SKU list")
    void shouldSkipWhenSkusEmpty() throws JsonProcessingException {
        var event = com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent.builder()
                .eventId(UUID.randomUUID()).occurredAt(Instant.now())
                .tenantId(TENANT_ID).storeId(STORE_ID).productId(PRODUCT_ID)
                .skus(Collections.emptyList())
                .build();

        when(objectMapper.readValue(eq(JSON), eq(com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent.class)))
                .thenReturn(event);

        consumer.consume(record("key-1", PAYLOAD));

        verifyNoInteractions(createInventoryUseCase);
    }

    @Test
    @DisplayName("calls createInventoryUseCase with correct tenantId, storeId, productId and SKUs")
    void shouldCreateInventoryWhenSkusPresent() throws JsonProcessingException {
        List<SKU> skus = List.of(SKU.of("SKU-001"), SKU.of("SKU-002"));

        var event = com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent.builder()
                .eventId(UUID.randomUUID()).occurredAt(Instant.now())
                .tenantId(TENANT_ID).storeId(STORE_ID).productId(PRODUCT_ID)
                .skus(skus)
                .build();

        when(objectMapper.readValue(eq(JSON), eq(com.dotran.oms.inventory.infrastructure.message.dto.ProductCreatedEvent.class)))
                .thenReturn(event);

        consumer.consume(record("key-1", PAYLOAD));

        ArgumentCaptor<CreateInventoryCmd> captor = ArgumentCaptor.forClass(CreateInventoryCmd.class);
        verify(createInventoryUseCase).create(captor.capture());

        CreateInventoryCmd cmd = captor.getValue();
        assertEquals(TENANT_ID, cmd.getTenantId().getValue());
        assertEquals(STORE_ID, cmd.getStoreId().getValue());
        assertEquals(PRODUCT_ID, cmd.getProductId().getValue());
        assertEquals(skus, cmd.getSkus());
    }
}
