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

/** Validates a payload against the copied contract {@code /contracts/events/<type>.schema.json}. */
public final class ContractSchemas {

    private ContractSchemas() {
    }

    public static Set<ValidationMessage> validate(String type, JsonNode payload) {
        try (InputStream in = ContractSchemas.class.getResourceAsStream("/contracts/events/" + type + ".schema.json")) {
            if (in == null) {
                throw new IllegalStateException("No schema for " + type);
            }
            // 2020-12 treats "format" as an annotation unless format assertions are on.
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
