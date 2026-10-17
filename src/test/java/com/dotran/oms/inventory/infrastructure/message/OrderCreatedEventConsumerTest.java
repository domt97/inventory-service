package com.dotran.oms.inventory.infrastructure.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCreatedEventConsumer")
class OrderCreatedEventConsumerTest {

    @InjectMocks
    private OrderCreatedEventConsumer consumer;

    @Test
    @DisplayName("processes OrderCreated event without throwing")
    void shouldConsumeWithoutThrowing() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "OrderCreated", 0, 0, "order-aggregate-id", "{\"orderId\":\"123\"}");

        assertDoesNotThrow(() -> consumer.consume(record));
    }

    @Test
    @DisplayName("handles null payload without throwing")
    void shouldHandleNullPayload() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "OrderCreated", 0, 0, "order-aggregate-id", null);

        assertDoesNotThrow(() -> consumer.consume(record));
    }
}
