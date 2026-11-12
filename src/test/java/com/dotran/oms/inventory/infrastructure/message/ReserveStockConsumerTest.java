package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.application.usecase.reservation.ReserveStockUseCase;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.support.TestMdc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReserveStockConsumer")
class ReserveStockConsumerTest {

    private static final ReserveStockResult RESERVED = new ReserveStockResult.Reserved(Instant.now(), List.of());

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private ReserveStockUseCase reserveStockUseCase;

    private final UUID eventId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID orderItemId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void stubResult() {
        lenient().when(reserveStockUseCase.reserve(any())).thenReturn(RESERVED);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private ReserveStockConsumer consumer() {
        return new ReserveStockConsumer(new OutboxPayloadDecoder(objectMapper), reserveStockUseCase, TestMdc.propagation());
    }

    /** A contract-valid command with one line. */
    private ObjectNode command() {
        ObjectNode command = objectMapper.createObjectNode()
                .put("eventId", eventId.toString())
                .put("eventType", "ReserveStock")
                .put("occurredAt", "2026-11-12T10:00:00Z")
                .put("merchantId", merchantId.toString())
                .put("storeId", storeId.toString())
                .put("orderId", orderId.toString());
        command.putArray("lines").add(line());
        return command;
    }

    private ObjectNode line() {
        return objectMapper.createObjectNode()
                .put("orderItemId", orderItemId.toString())
                .put("productId", productId.toString())
                .put("sku", "SKU-A")
                .put("quantity", 3);
    }

    private ObjectNode firstLine(ObjectNode command) {
        return (ObjectNode) command.get("lines").get(0);
    }

    /** As the Debezium EventRouter publishes it: a JSON string that contains the JSON. */
    private ConsumerRecord<String, String> record(ObjectNode command) throws JsonProcessingException {
        return rawRecord(objectMapper.writeValueAsString(command.toString()));
    }

    private ConsumerRecord<String, String> rawRecord(String value) {
        return new ConsumerRecord<>("ReserveStock", 2, 7L, orderId.toString(), value);
    }

    private ReserveStockCmd consumed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        consumer().consume(record);
        ArgumentCaptor<ReserveStockCmd> captor = ArgumentCaptor.forClass(ReserveStockCmd.class);
        verify(reserveStockUseCase).reserve(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("maps every field of the command")
    void mapsCommand() throws Exception {
        ReserveStockCmd cmd = consumed(record(command()));

        assertThat(cmd.getEventId()).isEqualTo(EventId.of(eventId));
        assertThat(cmd.getOccurredAt()).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(cmd.getMerchantId()).isEqualTo(MerchantId.of(merchantId));
        assertThat(cmd.getStoreId()).isEqualTo(StoreId.of(storeId));
        assertThat(cmd.getOrderId()).isEqualTo(OrderId.of(orderId));
        assertThat(cmd.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getOrderItemId()).isEqualTo(OrderItemId.of(orderItemId));
            assertThat(line.getProductId()).isEqualTo(ProductId.of(productId));
            assertThat(line.getSku()).isEqualTo(SKU.of("SKU-A"));
            assertThat(line.getQuantity()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("accepts a plain JSON object too (a message sent by hand)")
    void acceptsPlainJson() throws Exception {
        ReserveStockCmd cmd = consumed(rawRecord(command().toString()));

        assertThat(cmd.getOrderId()).isEqualTo(OrderId.of(orderId));
        assertThat(cmd.getLines()).hasSize(1);
    }

    @Test
    @DisplayName("ignores a tombstone (null value)")
    void ignoresTombstone() throws Exception {
        consumer().consume(rawRecord(null));

        verifyNoInteractions(reserveStockUseCase);
    }

    @Test
    @DisplayName("bad JSON, a non-object, a malformed UUID or instant is a JSON error and never reaches the use case")
    void badJson_isContractError() throws Exception {
        List<ConsumerRecord<String, String>> records = new ArrayList<>(List.of(
                rawRecord("{not json"), rawRecord("[1,2]"), rawRecord("\"just text\""),
                record(command().put("orderId", "not-a-uuid")),
                record(command().put("occurredAt", "yesterday"))));
        ObjectNode badLineId = command();
        firstLine(badLineId).put("orderItemId", "x");
        records.add(record(badLineId));
        ObjectNode badQuantity = command();
        firstLine(badQuantity).put("quantity", "many");
        records.add(record(badQuantity));
        ObjectNode linesNotArray = command();
        linesNotArray.put("lines", "none");
        records.add(record(linesNotArray));

        for (ConsumerRecord<String, String> record : records) {
            assertThatThrownBy(() -> consumer().consume(record)).as(record.value())
                    .isInstanceOf(JsonProcessingException.class);
        }
        verifyNoInteractions(reserveStockUseCase);
    }

    @Test
    @DisplayName("a missing id or occurredAt reaches the use case as null (not a wrapper around null), for its validation")
    void missingCommonFields_areNull() throws Exception {
        ObjectNode command = command();
        command.remove(List.of("eventId", "merchantId", "storeId", "occurredAt"));
        command.putNull("orderId");

        ReserveStockCmd cmd = consumed(record(command));

        assertThat(cmd.getEventId()).isNull();
        assertThat(cmd.getOccurredAt()).isNull();
        assertThat(cmd.getMerchantId()).isNull();
        assertThat(cmd.getStoreId()).isNull();
        assertThat(cmd.getOrderId()).isNull();
    }

    @Test
    @DisplayName("a wrong or missing eventType is a contract error and never reaches the use case")
    void wrongEventType_isContractError() throws Exception {
        ObjectNode missing = command();
        missing.remove("eventType");
        for (ObjectNode command : List.of(command().put("eventType", "ConfirmStockReservation"),
                command().put("eventType", "ReleaseStockReservation"), command().putNull("eventType"), missing)) {
            ConsumerRecord<String, String> record = record(command);
            assertThatThrownBy(() -> consumer().consume(record)).as(command.toString())
                    .isInstanceOf(InvalidStockCommandException.class);
        }
        verifyNoInteractions(reserveStockUseCase);
    }

    @Test
    @DisplayName("missing lines, a null line and missing line fields are passed on as null / 0 without an error here")
    void incompleteLines_arePassedOn() throws Exception {
        ObjectNode noLines = command();
        noLines.remove("lines");
        assertThat(consumed(record(noLines)).getLines()).isNull();

        org.mockito.Mockito.clearInvocations(reserveStockUseCase);
        ObjectNode emptyLines = command();
        emptyLines.putArray("lines");
        assertThat(consumed(record(emptyLines)).getLines()).isEmpty();

        org.mockito.Mockito.clearInvocations(reserveStockUseCase);
        ObjectNode sparse = command();
        ArrayNode lines = sparse.putArray("lines");
        lines.addNull();
        lines.addObject();
        List<ReserveStockCmd.Line> mapped = consumed(record(sparse)).getLines();
        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0)).isNull();
        assertThat(mapped.get(1).getOrderItemId()).isNull();
        assertThat(mapped.get(1).getProductId()).isNull();
        assertThat(mapped.get(1).getSku()).isNull();
        assertThat(mapped.get(1).getQuantity()).isZero();
    }

    @Test
    @DisplayName("quantity: a fraction or a number beyond long is a contract error here; 0 and -1 are passed on; \"5\" is read as 5")
    void quantity() throws Exception {
        for (String bad : List.of("2.7", "1e30")) {
            ObjectNode command = command();
            firstLine(command).set("quantity", objectMapper.readTree(bad));
            ConsumerRecord<String, String> record = record(command);
            assertThatThrownBy(() -> consumer().consume(record)).as(bad).isInstanceOf(InvalidStockCommandException.class);
        }
        verifyNoInteractions(reserveStockUseCase);

        Map<String, Long> passedOn = Map.of("0", 0L, "-1", -1L, "\"5\"", 5L, "2.0", 2L);
        for (Map.Entry<String, Long> entry : passedOn.entrySet()) {
            org.mockito.Mockito.clearInvocations(reserveStockUseCase);
            ObjectNode command = command();
            firstLine(command).set("quantity", objectMapper.readTree(entry.getKey()));
            assertThat(consumed(record(command)).getLines().get(0).getQuantity()).as(entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("a business outcome (rejected) is a normal return")
    void rejection_isNotAnError() throws Exception {
        lenient().when(reserveStockUseCase.reserve(any()))
                .thenReturn(new ReserveStockResult.Rejected(OrderRejectionReason.STALE_COMMAND, List.of()));

        assertThatCode(() -> consumer().consume(record(command()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an exception of the use case is thrown on unchanged, for the error handler")
    void useCaseFailure_propagates() throws Exception {
        RuntimeException mismatch = new StockCommandOwnershipMismatchException("other merchant");
        doThrow(mismatch).when(reserveStockUseCase).reserve(any());
        assertThatThrownBy(() -> consumer().consume(record(command()))).isSameAs(mismatch);

        RuntimeException transientFailure = new InvalidReservationStateException("lost to the expiry");
        doThrow(transientFailure).when(reserveStockUseCase).reserve(any());
        assertThatThrownBy(() -> consumer().consume(record(command()))).isSameAs(transientFailure);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("MDC carries record coordinates and command ids during the use case; empty afterwards and after a contract error")
    void setsAndClearsMdc() throws Exception {
        Map<String, String> seen = new HashMap<>();
        doAnswer(inv -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return RESERVED;
        }).when(reserveStockUseCase).reserve(any());

        consumer().consume(record(command()));

        assertThat(seen).containsEntry("requestId", "ReserveStock-2-7")
                .containsEntry("kafkaTopic", "ReserveStock")
                .containsEntry("kafkaPartition", "2")
                .containsEntry("kafkaOffset", "7")
                .containsEntry("eventId", eventId.toString())
                .containsEntry("merchantId", merchantId.toString())
                .containsEntry("storeId", storeId.toString())
                .containsEntry("orderId", orderId.toString());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        assertThatThrownBy(() -> consumer().consume(record(command().put("eventType", "Other"))))
                .isInstanceOf(InvalidStockCommandException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("correlationContext header sets requestId during the use case; without it the record coordinates are used")
    void headerRequestId() throws Exception {
        List<String> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(MDC.get("requestId"));
            return RESERVED;
        }).when(reserveStockUseCase).reserve(any());

        ConsumerRecord<String, String> withHeader = record(command());
        withHeader.headers().add(MdcPropagation.HEADER, TestMdc.header("{\"requestId\":\"web-reserve\"}"));
        consumer().consume(withHeader);
        consumer().consume(record(command()));

        assertThat(seen).containsExactly("web-reserve", "ReserveStock-2-7");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
