package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.infrastructure.message.dto.ProductUpdatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboxPayloadDecoder")
class OutboxPayloadDecoderTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final OutboxPayloadDecoder decoder = new OutboxPayloadDecoder(objectMapper);

    private final UUID productId = UUID.randomUUID();
    private final String eventJson = """
            {"eventType":"ProductUpdated","productId":"%s","version":3,"skus":[]}""".formatted(productId);

    @Test
    @DisplayName("reads a JSON string that wraps the event (Debezium EventRouter format)")
    void readsStringWrappedJson() throws Exception {
        String kafkaValue = objectMapper.writeValueAsString(eventJson);

        ProductUpdatedEvent event = decoder.decode(kafkaValue, ProductUpdatedEvent.class);

        assertThat(event.getProductId()).isEqualTo(productId);
        assertThat(event.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("reads a plain JSON object too")
    void readsPlainJsonObject() throws Exception {
        ProductUpdatedEvent event = decoder.decode(eventJson, ProductUpdatedEvent.class);

        assertThat(event.getProductId()).isEqualTo(productId);
    }

    @Test
    @DisplayName("rejects garbage and non-object JSON with a JsonProcessingException (sent to the DLT, not retried)")
    void rejectsGarbage() {
        assertThatThrownBy(() -> decoder.decode("not json", ProductUpdatedEvent.class)).isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> decoder.decode("42", ProductUpdatedEvent.class)).isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> decoder.decode("\"[1,2]\"", ProductUpdatedEvent.class)).isInstanceOf(JsonProcessingException.class);
    }
}
