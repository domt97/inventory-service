package com.dotran.oms.inventory.infrastructure.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads an outbox event from a Kafka value. The Debezium EventRouter publishes the payload as a JSON string that
 * contains the event JSON; a plain JSON object is accepted too, so a connector change does not break consumers.
 */
@Component
@RequiredArgsConstructor
public class OutboxPayloadDecoder {

    private final ObjectMapper objectMapper;

    public <T> T decode(String value, Class<T> type) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(value);
        if (node != null && node.isTextual()) {
            node = objectMapper.readTree(node.asText());
        }
        if (node == null || !node.isObject()) {
            throw new JsonMappingException(null, "Event payload is not a JSON object");
        }
        return objectMapper.treeToValue(node, type);
    }
}
