package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.inventory.support.TestMdc;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KafkaMdc")
class KafkaMdcTest {

    private final MdcPropagation propagation = TestMdc.propagation();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("ProductCreated", 2, 41L, "key", "value");
    }

    private static ConsumerRecord<String, String> record(String headerJson) {
        ConsumerRecord<String, String> record = record();
        record.headers().add(MdcPropagation.HEADER, headerJson == null ? null : TestMdc.header(headerJson));
        return record;
    }

    @Test
    @DisplayName("a valid correlationContext header sets requestId; record coordinates are still logged")
    void headerSetsRequestId() {
        try (MdcScope ignored = KafkaMdc.open(record("{\"requestId\":\"e2e-q46-1\"}"), propagation)) {
            assertThat(MDC.get("requestId")).isEqualTo("e2e-q46-1");
            assertThat(MDC.get("kafkaTopic")).isEqualTo("ProductCreated");
            assertThat(MDC.get("kafkaPartition")).isEqualTo("2");
            assertThat(MDC.get("kafkaOffset")).isEqualTo("41");
        }
    }

    @Test
    @DisplayName("no header, a null value, {} or an invalid header fall back to topic-partition-offset")
    void fallsBackToCoordinates() {
        for (ConsumerRecord<String, String> record : java.util.List.of(record(), record(null), record("{}"),
                record("not json"), record("{\"requestId\":\"bad id\\n\"}"))) {
            try (MdcScope ignored = KafkaMdc.open(record, propagation)) {
                assertThat(MDC.get("requestId")).isEqualTo("ProductCreated-2-41");
            }
        }
    }

    @Test
    @DisplayName("with two headers the last one wins")
    void lastHeaderWins() {
        ConsumerRecord<String, String> record = record("{\"requestId\":\"first\"}");
        record.headers().add(MdcPropagation.HEADER, TestMdc.header("{\"requestId\":\"second\"}"));

        try (MdcScope ignored = KafkaMdc.open(record, propagation)) {
            assertThat(MDC.get("requestId")).isEqualTo("second");
        }
    }

    @Test
    @DisplayName("the header cannot set message or entity keys")
    void headerCannotSetOtherKeys() {
        String header = "{\"requestId\":\"r1\",\"kafkaOffset\":\"999\",\"eventId\":\"3f1c2a9e-7d4b-4c1e-9a0b-1234567890ab\","
                + "\"merchantId\":\"3f1c2a9e-7d4b-4c1e-9a0b-1234567890ab\"}";

        try (MdcScope ignored = KafkaMdc.open(record(header), propagation)) {
            assertThat(MDC.get("kafkaOffset")).isEqualTo("41");
            assertThat(MDC.get("eventId")).isNull();
            assertThat(MDC.get("merchantId")).isNull();
        }
    }

    @Test
    @DisplayName("closing restores the previous MDC")
    void restoresOnClose() {
        MDC.put("outer", "kept");
        try (MdcScope ignored = KafkaMdc.open(record("{\"requestId\":\"r1\"}"), propagation)) {
            assertThat(MDC.get("requestId")).isEqualTo("r1");
        }
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("outer")).isEqualTo("kept");
    }
}
