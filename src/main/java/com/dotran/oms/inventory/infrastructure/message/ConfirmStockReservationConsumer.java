package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.usecase.reservation.ConfirmStockReservationUseCase;
import com.dotran.oms.inventory.infrastructure.message.dto.ConfirmStockReservationCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entry point of the ConfirmStockReservation command (ADR 0006, oms-workspace flows/order-stock-reservation.md).
 * Only maps and calls the use case, like {@link ReserveStockConsumer}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfirmStockReservationConsumer {

    static final String TYPE = "ConfirmStockReservation";

    private final OutboxPayloadDecoder payloadDecoder;
    private final ConfirmStockReservationUseCase confirmStockReservationUseCase;
    private final MdcPropagation mdcPropagation;

    @KafkaListener(
            // A literal, like the other listeners: oms-workspace scripts/extract_facts.py reads it from the source.
            topics = "ConfirmStockReservation",
            groupId = "inventory-confirm-stock-reservation",
            concurrency = "${app.kafka.stock-commands.concurrency:1}",
            // Few records per poll: see app.kafka.stock-commands in application.yaml.
            properties = "max.poll.records=5"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
            if (record.value() == null) {
                log.warn("ConfirmStockReservation record without a value (tombstone); ignored");
                return;
            }

            ConfirmStockReservationCommand command =
                    payloadDecoder.decode(record.value(), ConfirmStockReservationCommand.class);
            mdc.put(MdcKeys.EVENT_ID, command.getEventId())
                    .put(MdcKeys.MERCHANT_ID, command.getMerchantId())
                    .put(MdcKeys.STORE_ID, command.getStoreId())
                    .put(StockCommandMapping.ORDER_ID, command.getOrderId());
            StockCommandMapping.requireType(TYPE, command.getEventType());

            // Inside the scope: the reply's correlation context is taken from this thread's MDC.
            ConfirmStockReservationResult result = confirmStockReservationUseCase.confirm(toCmd(command));

            log.info("ConfirmStockReservation handled: eventId={}, orderId={}, result={}", command.getEventId(),
                    command.getOrderId(), result.getClass().getSimpleName());
        }
    }

    private static ConfirmStockReservationCmd toCmd(ConfirmStockReservationCommand command) {
        return ConfirmStockReservationCmd.builder()
                .eventId(StockCommandMapping.id(command.getEventId(), EventId::of))
                .occurredAt(command.getOccurredAt())
                .merchantId(StockCommandMapping.id(command.getMerchantId(), MerchantId::of))
                .storeId(StockCommandMapping.id(command.getStoreId(), StoreId::of))
                .orderId(StockCommandMapping.id(command.getOrderId(), OrderId::of))
                .build();
    }
}
