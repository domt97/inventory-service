package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.inventory.application.event.OutboxMessage;
import com.dotran.oms.inventory.application.event.StockReply;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.WalSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5 outbox: rows inserted and deleted in one transaction, visible only in the WAL (what Debezium reads). */
@DisplayName("Outbox SQL (Postgres, WAL)")
class OutboxSqlIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OutboxEventRepository outbox;

    private WalSlot wal;

    @BeforeEach
    void slot() {
        wal = WalSlot.create(jdbc, "it_outbox_sql");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private OutboxMessage message(UUID order) {
        return OutboxMessage.of(new StockReply.Expired(UUID.randomUUID(), StockReply.Expired.TYPE,
                Instant.parse("2026-11-09T10:00:00.123456Z"), UUID.randomUUID(), UUID.randomUUID(), order, null,
                List.of(new StockReply.ExpiredLine(UUID.randomUUID(), "SKU-'A'"))));
    }

    @Test
    @DisplayName("a committed saveAll leaves the table empty; the WAL has one INSERT then one DELETE per message, one xid")
    void insertThenDelete() throws Exception {
        OutboxMessage first = message(UUID.randomUUID());
        OutboxMessage second = message(UUID.randomUUID());

        try (MdcScope ignored = MdcScope.open().put("requestId", "it-req-1")) {
            tx.executeWithoutResult(st -> outbox.saveAll(List.of(first, second)));
        }

        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
        List<WalSlot.Change> changes = wal.changes().stream().filter(WalSlot.Change::isOutbox).toList();
        assertThat(changes).extracting(WalSlot.Change::op).containsExactly("INSERT", "INSERT", "DELETE", "DELETE");
        assertThat(changes).extracting(WalSlot.Change::xid).containsOnly(changes.get(0).xid());
        assertThat(changes).extracting(WalSlot.Change::id).containsExactlyInAnyOrder(
                first.eventId().toString(), second.eventId().toString(),
                first.eventId().toString(), second.eventId().toString());

        WalSlot.Change insert = changes.get(0);
        JsonNode payload = JSON.readTree(insert.payload());
        assertThat(payload.get("eventId").asText()).isEqualTo(insert.id());
        assertThat(payload.get("causationId").isNull()).isTrue();
        assertThat(payload.get("lines").get(0).get("sku").asText()).isEqualTo("SKU-'A'");
        assertThat(JSON.readTree(insert.correlation()).get("requestId").asText()).isEqualTo("it-req-1");
    }

    @Test
    @DisplayName("a rolled-back transaction leaves nothing in the WAL")
    void rollback() {
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
            outbox.saveAll(List.of(message(UUID.randomUUID())));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(wal.changes()).noneMatch(WalSlot.Change::isOutbox);
    }

    @Test
    @DisplayName("needs a surrounding transaction (MANDATORY)")
    void mandatory() {
        assertThatThrownBy(() -> outbox.saveAll(List.of(message(UUID.randomUUID()))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
