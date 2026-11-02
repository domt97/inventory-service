package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcScope;
import lombok.experimental.UtilityClass;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * MDC for one Kafka record. {@code requestId} is derived from the record's coordinates
 * ({@code topic-partition-offset}), so every redelivery and retry of the same record logs under the same id.
 * Open it first in every listener and keep the whole body inside the scope; the DLT recoverer
 * ({@code KafkaErrorHandlingConfig}) opens it too, so the DLT line has the same requestId as the attempts.
 */
@UtilityClass
public class KafkaMdc {

    public static MdcScope open(ConsumerRecord<?, ?> record) {
        return MdcScope.open()
                .put(MdcKeys.REQUEST_ID, record.topic() + "-" + record.partition() + "-" + record.offset())
                .put(MdcKeys.KAFKA_TOPIC, record.topic())
                .put(MdcKeys.KAFKA_PARTITION, record.partition())
                .put(MdcKeys.KAFKA_OFFSET, record.offset());
    }
}
