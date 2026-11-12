package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.application.usecase.reservation.ReserveStockUseCase;
import com.dotran.oms.inventory.infrastructure.message.dto.ReserveStockCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entry point of the ReserveStock command (ADR 0006, oms-workspace flows/order-stock-reservation.md). Only maps and
 * calls the use case: the use case is idempotent by the order header and writes the reply to the outbox, so a
 * business outcome (also a rejection) is a normal return and every exception goes to the error handler unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReserveStockConsumer {

    static final String TYPE = "ReserveStock";

    private final OutboxPayloadDecoder payloadDecoder;
    private final ReserveStockUseCase reserveStockUseCase;
    private final MdcPropagation mdcPropagation;

    @KafkaListener(
            // A literal, like the other listeners: oms-workspace scripts/extract_facts.py reads it from the source.
            topics = "ReserveStock",
            groupId = "inventory-reserve-stock",
            concurrency = "${app.kafka.stock-commands.concurrency:1}",
            // Few records per poll: see app.kafka.stock-commands in application.yaml.
            properties = "max.poll.records=5"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
            if (record.value() == null) {
                log.warn("ReserveStock record without a value (tombstone); ignored");
                return;
            }

            ReserveStockCommand command = payloadDecoder.decode(record.value(), ReserveStockCommand.class);
            mdc.put(MdcKeys.EVENT_ID, command.getEventId())
                    .put(MdcKeys.MERCHANT_ID, command.getMerchantId())
                    .put(MdcKeys.STORE_ID, command.getStoreId())
                    .put(StockCommandMapping.ORDER_ID, command.getOrderId());
            StockCommandMapping.requireType(TYPE, command.getEventType());

            // Inside the scope: the reply's correlation context is taken from this thread's MDC.
            ReserveStockResult result = reserveStockUseCase.reserve(toCmd(command));

            log.info("ReserveStock handled: eventId={}, orderId={}, result={}", command.getEventId(),
                    command.getOrderId(), result.getClass().getSimpleName());
        }
    }

    private static ReserveStockCmd toCmd(ReserveStockCommand command) {
        List<ReserveStockCmd.Line> lines = command.getLines() == null ? null : command.getLines().stream()
                .map(line -> line == null ? null : ReserveStockCmd.Line.builder()
                        .orderItemId(StockCommandMapping.id(line.getOrderItemId(), OrderItemId::of))
                        .productId(StockCommandMapping.id(line.getProductId(), ProductId::of))
                        .sku(StockCommandMapping.sku(line.getSku()))
                        .quantity(StockCommandMapping.quantity(line.getQuantity()))
                        .build())
                // A null line stays null (Stream.toList allows it): the use case's validation rejects it.
                .toList();

        return ReserveStockCmd.builder()
                .eventId(StockCommandMapping.id(command.getEventId(), EventId::of))
                .occurredAt(command.getOccurredAt())
                .merchantId(StockCommandMapping.id(command.getMerchantId(), MerchantId::of))
                .storeId(StockCommandMapping.id(command.getStoreId(), StoreId::of))
                .orderId(StockCommandMapping.id(command.getOrderId(), OrderId::of))
                .lines(lines)
                .build();
    }
}
