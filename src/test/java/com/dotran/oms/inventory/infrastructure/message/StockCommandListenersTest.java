package com.dotran.oms.inventory.infrastructure.message;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener settings the contract and the sizing rely on (topic = message type, the group named in the command
 * schema, few records per poll, configurable concurrency). Only the annotation values: that the container applies
 * them is a manual check against a real broker.
 */
@DisplayName("Stock command listeners")
class StockCommandListenersTest {

    private static void assertListener(Class<?> consumer, String topic, String group) throws NoSuchMethodException {
        KafkaListener listener = consumer.getMethod("consume", ConsumerRecord.class).getAnnotation(KafkaListener.class);

        assertThat(listener).as("@KafkaListener on %s", consumer.getSimpleName()).isNotNull();
        assertThat(listener.topics()).containsExactly(topic);
        assertThat(listener.groupId()).isEqualTo(group);
        assertThat(listener.properties()).containsExactly("max.poll.records=5");
        assertThat(listener.concurrency()).isEqualTo("${app.kafka.stock-commands.concurrency:1}");
    }

    @Test
    @DisplayName("each command has its own topic and group, 5 records per poll and the concurrency property")
    void listeners() throws Exception {
        assertListener(ReserveStockConsumer.class, "ReserveStock", "inventory-reserve-stock");
        assertListener(ConfirmStockReservationConsumer.class, "ConfirmStockReservation",
                "inventory-confirm-stock-reservation");
        assertListener(ReleaseStockReservationConsumer.class, "ReleaseStockReservation",
                "inventory-release-stock-reservation");
    }
}
