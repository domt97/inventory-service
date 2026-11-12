package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.usecase.reservation.ReleaseStockReservationUseCase;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.support.TestMdc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReleaseStockReservationConsumer")
class ReleaseStockReservationConsumerTest {

    private static final ReleaseStockReservationResult RESULT = new ReleaseStockReservationResult(true, List.of());

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private ReleaseStockReservationUseCase useCase;

    private final UUID eventId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void stubResult() {
        lenient().when(useCase.release(any())).thenReturn(RESULT);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private ReleaseStockReservationConsumer consumer() {
        return new ReleaseStockReservationConsumer(new OutboxPayloadDecoder(objectMapper), useCase, TestMdc.propagation());
    }

    /** A contract-valid command. */
    private ObjectNode command() {
        return objectMapper.createObjectNode()
                .put("eventId", eventId.toString())
                .put("eventType", "ReleaseStockReservation")
                .put("occurredAt", "2026-11-12T10:00:00Z")
                .put("merchantId", merchantId.toString())
                .put("storeId", storeId.toString())
                .put("orderId", orderId.toString())
                .put("restock", true)
                .put("reason", "CUSTOMER_CANCELLED");
    }

    /** As the Debezium EventRouter publishes it: a JSON string that contains the JSON. */
    private ConsumerRecord<String, String> record(ObjectNode command) throws JsonProcessingException {
        return rawRecord(objectMapper.writeValueAsString(command.toString()));
    }

    private ConsumerRecord<String, String> rawRecord(String value) {
        return new ConsumerRecord<>("ReleaseStockReservation", 2, 7L, orderId.toString(), value);
    }

    private ReleaseStockReservationCmd consumed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        consumer().consume(record);
        ArgumentCaptor<ReleaseStockReservationCmd> captor = ArgumentCaptor.forClass(ReleaseStockReservationCmd.class);
        verify(useCase).release(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("maps every field of the command")
    void mapsCommand() throws Exception {
        ReleaseStockReservationCmd cmd = consumed(record(command()));

        assertThat(cmd.getEventId()).isEqualTo(EventId.of(eventId));
        assertThat(cmd.getOccurredAt()).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(cmd.getMerchantId()).isEqualTo(MerchantId.of(merchantId));
        assertThat(cmd.getStoreId()).isEqualTo(StoreId.of(storeId));
        assertThat(cmd.getOrderId()).isEqualTo(OrderId.of(orderId));
        assertThat(cmd.getRestock()).isTrue();
        assertThat(cmd.getReason()).isEqualTo("CUSTOMER_CANCELLED");
    }

    @Test
    @DisplayName("accepts a plain JSON object too (a message sent by hand)")
    void acceptsPlainJson() throws Exception {
        assertThat(consumed(rawRecord(command().toString())).getOrderId()).isEqualTo(OrderId.of(orderId));
    }

    @Test
    @DisplayName("ignores a tombstone (null value)")
    void ignoresTombstone() throws Exception {
        consumer().consume(rawRecord(null));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("bad JSON, a non-object, a malformed UUID or instant is a JSON error and never reaches the use case")
    void badJson_isContractError() throws Exception {
        for (ConsumerRecord<String, String> record : List.of(
                rawRecord("{not json"), rawRecord("[1,2]"), rawRecord("\"just text\""),
                record(command().put("orderId", "not-a-uuid")),
                record(command().put("occurredAt", "yesterday")))) {
            assertThatThrownBy(() -> consumer().consume(record)).as(record.value())
                    .isInstanceOf(JsonProcessingException.class);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("a missing id or occurredAt reaches the use case as null (not a wrapper around null), for its validation")
    void missingCommonFields_areNull() throws Exception {
        ObjectNode command = command();
        command.remove(List.of("eventId", "merchantId", "storeId", "occurredAt"));
        command.putNull("orderId");

        ReleaseStockReservationCmd cmd = consumed(record(command));

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
                command().put("eventType", "ReserveStock"), command().putNull("eventType"), missing)) {
            ConsumerRecord<String, String> record = record(command);
            assertThatThrownBy(() -> consumer().consume(record)).as(command.toString())
                    .isInstanceOf(InvalidStockCommandException.class);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("restock false is mapped as false; a missing restock or reason reaches the use case as null")
    void restockAndReason_passedOn() throws Exception {
        assertThat(consumed(record(command().put("restock", false))).getRestock()).isFalse();

        clearInvocations(useCase);
        ObjectNode command = command();
        command.remove(List.of("restock", "reason"));
        ReleaseStockReservationCmd cmd = consumed(record(command));
        assertThat(cmd.getRestock()).isNull();
        assertThat(cmd.getReason()).isNull();

        clearInvocations(useCase);
        String longReason = "R".repeat(51);
        assertThat(consumed(record(command().put("reason", longReason))).getReason()).isEqualTo(longReason);
    }

    @Test
    @DisplayName("restock that is not a JSON boolean (1, 0, \"true\", an object) is a contract error and never reaches the use case")
    void restockNotBoolean_isContractError() throws Exception {
        for (String value : List.of("1", "0", "\"true\"", "\"false\"", "{}", "[true]")) {
            ObjectNode command = command();
            command.set("restock", objectMapper.readTree(value));
            ConsumerRecord<String, String> record = record(command);
            assertThatThrownBy(() -> consumer().consume(record)).as(value)
                    .isInstanceOf(InvalidStockCommandException.class);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("a business outcome is a normal return")
    void businessOutcome_isNotAnError() throws Exception {
        lenient().when(useCase.release(any())).thenReturn(new ReleaseStockReservationResult(false, List.of()));

        assertThatCode(() -> consumer().consume(record(command()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an exception of the use case is thrown on unchanged, for the error handler")
    void useCaseFailure_propagates() throws Exception {
        RuntimeException mismatch = new StockCommandOwnershipMismatchException("other merchant");
        doThrow(mismatch).when(useCase).release(any());
        assertThatThrownBy(() -> consumer().consume(record(command()))).isSameAs(mismatch);

        RuntimeException lostToExpiry = new InvalidReservationStateException("lost to the expiry");
        doThrow(lostToExpiry).when(useCase).release(any());
        assertThatThrownBy(() -> consumer().consume(record(command()))).isSameAs(lostToExpiry);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("MDC carries record coordinates and command ids during the use case; empty afterwards and after a contract error")
    void setsAndClearsMdc() throws Exception {
        Map<String, String> seen = new HashMap<>();
        doAnswer(inv -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return RESULT;
        }).when(useCase).release(any());

        consumer().consume(record(command()));

        assertThat(seen).containsEntry("requestId", "ReleaseStockReservation-2-7")
                .containsEntry("kafkaTopic", "ReleaseStockReservation")
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
            return RESULT;
        }).when(useCase).release(any());

        ConsumerRecord<String, String> withHeader = record(command());
        withHeader.headers().add(MdcPropagation.HEADER, TestMdc.header("{\"requestId\":\"web-cmd\"}"));
        consumer().consume(withHeader);
        consumer().consume(record(command()));

        assertThat(seen).containsExactly("web-cmd", "ReleaseStockReservation-2-7");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
