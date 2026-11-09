package com.dotran.oms.inventory.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/** Validates a payload against the copied reply contract {@code /contracts/events/<eventType>.schema.json}. */
public final class ReplySchemas {

    private ReplySchemas() {
    }

    public static Set<ValidationMessage> validate(JsonNode payload) {
        String type = payload.get("eventType").asText();
        try (InputStream in = ReplySchemas.class.getResourceAsStream("/contracts/events/" + type + ".schema.json")) {
            if (in == null) {
                throw new IllegalStateException("No schema for " + type);
            }
            @SuppressWarnings("deprecation") // the setter works on every 1.x release
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            config.setFormatAssertionsEnabled(true);
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
            return schema.validate(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
