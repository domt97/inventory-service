package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import lombok.experimental.UtilityClass;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.util.Map;

/**
 * MDC for one Kafka record. The producer's correlation keys come from the {@code correlationContext} header
 * (ADR 0004; validated by {@link MdcPropagation#restore}), so {@code requestId} is the id of the whole flow, e.g. the
 * browser's X-Request-Id that created the product. Without a valid header {@code requestId} falls back to the record's
 * {@code topic-partition-offset}. Either way it is the same on every retry; the record's position is always in
 * {@code kafkaTopic}/{@code kafkaPartition}/{@code kafkaOffset}.
 * <p>
 * Open it first in every listener and keep the whole body inside the scope; payload ids are put afterwards (the header
 * never carries them). The DLT recoverer ({@code KafkaErrorHandlingConfig}) opens it too, so the DLT line carries the same ids.
 */
@UtilityClass
public class KafkaMdc {

    public static MdcScope open(ConsumerRecord<?, ?> record, MdcPropagation propagation) {
        // Coordinates first, so a WARN about a bad header names the record. restore() never returns them.
        MdcScope scope = MdcScope.open()
                .put(MdcKeys.KAFKA_TOPIC, record.topic())
                .put(MdcKeys.KAFKA_PARTITION, record.partition())
                .put(MdcKeys.KAFKA_OFFSET, record.offset());

        Header header = record.headers() == null ? null : record.headers().lastHeader(MdcPropagation.HEADER);
        Map<String, String> propagated = propagation.restore(header == null ? null : header.value());
        propagated.forEach(scope::put);
        if (!propagated.containsKey(MdcKeys.REQUEST_ID)) {
            scope.put(MdcKeys.REQUEST_ID, record.topic() + "-" + record.partition() + "-" + record.offset());
        }
        return scope;
    }
}
