package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.usecase.reservation.ReleaseStockReservationUseCase;
import com.dotran.oms.inventory.infrastructure.message.dto.ReleaseStockReservationCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entry point of the ReleaseStockReservation command (ADR 0006, oms-workspace flows/order-stock-reservation.md).
 * Only maps and calls the use case, like {@link ReserveStockConsumer}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReleaseStockReservationConsumer {

    static final String TYPE = "ReleaseStockReservation";

    private final OutboxPayloadDecoder payloadDecoder;
    private final ReleaseStockReservationUseCase releaseStockReservationUseCase;
    private final MdcPropagation mdcPropagation;

    @KafkaListener(
            // A literal, like the other listeners: oms-workspace scripts/extract_facts.py reads it from the source.
            topics = "ReleaseStockReservation",
            groupId = "inventory-release-stock-reservation",
            concurrency = "${app.kafka.stock-commands.concurrency:1}",
            // Few records per poll: see app.kafka.stock-commands in application.yaml.
            properties = "max.poll.records=5"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
            if (record.value() == null) {
                log.warn("ReleaseStockReservation record without a value (tombstone); ignored");
                return;
            }

            ReleaseStockReservationCommand command =
                    payloadDecoder.decode(record.value(), ReleaseStockReservationCommand.class);
            mdc.put(MdcKeys.EVENT_ID, command.getEventId())
                    .put(MdcKeys.MERCHANT_ID, command.getMerchantId())
                    .put(MdcKeys.STORE_ID, command.getStoreId())
                    .put(StockCommandMapping.ORDER_ID, command.getOrderId());
            StockCommandMapping.requireType(TYPE, command.getEventType());

            // Inside the scope: the reply's correlation context is taken from this thread's MDC.
            ReleaseStockReservationResult result = releaseStockReservationUseCase.release(toCmd(command));

            log.info("ReleaseStockReservation handled: eventId={}, orderId={}, restock={}", command.getEventId(),
                    command.getOrderId(), result.restock());
        }
    }

    private static ReleaseStockReservationCmd toCmd(ReleaseStockReservationCommand command) {
        return ReleaseStockReservationCmd.builder()
                .eventId(StockCommandMapping.id(command.getEventId(), EventId::of))
                .occurredAt(command.getOccurredAt())
                .merchantId(StockCommandMapping.id(command.getMerchantId(), MerchantId::of))
                .storeId(StockCommandMapping.id(command.getStoreId(), StoreId::of))
                .orderId(StockCommandMapping.id(command.getOrderId(), OrderId::of))
                .restock(StockCommandMapping.restock(command.getRestock()))
                // Caller-chosen text: the use case bounds its length; it is stored, never logged here.
                .reason(command.getReason())
                .build();
    }
}
