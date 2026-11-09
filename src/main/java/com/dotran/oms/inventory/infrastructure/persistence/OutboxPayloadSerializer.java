package com.dotran.oms.inventory.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * JSON of the outbox payloads, independent of the application's ObjectMapper: ISO-8601 instants and nulls always
 * written (the reply schemas require {@code causationId: null}, {@code orderReason: null}).
 */
@Component
public class OutboxPayloadSerializer {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox payload " + value.getClass().getSimpleName(), e);
        }
    }
}
