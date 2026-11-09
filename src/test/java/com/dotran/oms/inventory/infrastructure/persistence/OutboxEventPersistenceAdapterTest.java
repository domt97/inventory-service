package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.inventory.application.event.OutboxMessage;
import com.dotran.oms.inventory.application.event.StockReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxEventPersistenceAdapter")
class OutboxEventPersistenceAdapterTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private MdcPropagation mdcPropagation;

    private OutboxEventPersistenceAdapter adapter;

    private final UUID merchant = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new OutboxEventPersistenceAdapter(jdbc, new OutboxPayloadSerializer(), mdcPropagation);
    }

    private OutboxMessage message() {
        return OutboxMessage.of(new StockReply.Expired(UUID.randomUUID(), StockReply.Expired.TYPE,
                Instant.parse("2026-11-09T10:00:00Z"), merchant, UUID.randomUUID(), order, null,
                List.of(new StockReply.ExpiredLine(UUID.randomUUID(), "SKU-A"))));
    }

    @Test
    @DisplayName("inserts every message with id = eventId and the JSON payload, then deletes exactly those ids")
    void insertsThenDeletes() {
        OutboxMessage first = message();
        OutboxMessage second = message();
        when(mdcPropagation.capture()).thenReturn(Map.of("requestId", "req-1"));
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1, Statement.SUCCESS_NO_INFO});
        when(jdbc.update(anyString(), anyMap())).thenReturn(2);

        adapter.saveAll(List.of(first, second));

        ArgumentCaptor<SqlParameterSource[]> rows = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc).batchUpdate(anyString(), rows.capture());
        SqlParameterSource row = rows.getValue()[0];
        assertThat(row.getValue("id")).isEqualTo(first.eventId());
        assertThat(row.getValue("aggregateId")).isEqualTo(order);
        assertThat(row.getValue("aggregateType")).isEqualTo("StockReservation");
        assertThat(row.getValue("eventType")).isEqualTo("StockReservationExpired");
        assertThat(row.getValue("merchantId")).isEqualTo(merchant);
        assertThat((String) row.getValue("payload")).contains("\"eventId\":\"" + first.eventId() + "\"")
                .contains("\"causationId\":null");
        assertThat(row.getValue("correlation")).isEqualTo("{\"requestId\":\"req-1\"}");
        verify(jdbc).update(anyString(), eq(Map.of("ids", List.of(first.eventId(), second.eventId()))));
    }

    @Test
    @DisplayName("an empty MDC gives the correlation {}")
    void emptyCorrelation() {
        when(mdcPropagation.capture()).thenReturn(Map.of());
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1});
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        adapter.saveAll(List.of(message()));

        ArgumentCaptor<SqlParameterSource[]> rows = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc).batchUpdate(anyString(), rows.capture());
        assertThat(rows.getValue()[0].getValue("correlation")).isEqualTo("{}");
    }

    @Test
    @DisplayName("an empty list runs no SQL")
    void emptyList() {
        adapter.saveAll(List.of());

        verifyNoInteractions(jdbc, mdcPropagation);
    }

    @Test
    @DisplayName("a delete that does not remove every inserted row is an error")
    void deleteCountChecked() {
        when(mdcPropagation.capture()).thenReturn(Map.of());
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1, 1});
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        assertThatThrownBy(() -> adapter.saveAll(List.of(message(), message())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an insert that writes no row is an error and nothing is deleted")
    void insertCountChecked() {
        when(mdcPropagation.capture()).thenReturn(Map.of());
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{0});

        assertThatThrownBy(() -> adapter.saveAll(List.of(message()))).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), anyMap());
    }
}
