package com.dotran.example.inventory.infrastructure.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCreatedEventConsumer {

//    @KafkaListener(
//            topics = "OrderCreated",
//            groupId = "order-consumer"
//    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String aggregateId = record.key();
        log.info("Received OrderCreated event: aggregateId={}", aggregateId);

        String payload = record.value();

        log.info("Finished processing OrderCreated event: aggregateId={}", aggregateId);
    }
}
