package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.repository.ExpiryCursor;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.ReplySchemas;
import com.dotran.oms.inventory.support.StockCommands;
import com.dotran.oms.inventory.support.WalSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every stock command decision publishes its reply in the same transaction (one outbox INSERT + DELETE in the xid of
 * the business change), duplicates publish again, contract errors publish nothing. Read from the WAL.
 */
@DisplayName("Stock replies end to end (Postgres, WAL)")
class StockRepliesIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ReserveStockService reserveService;
    @Autowired
    private ConfirmStockReservationService confirmService;
    @Autowired
    private ReleaseStockReservationService releaseService;
    @Autowired
    private ExpireByInventoryTransaction expireTransaction;
    @Autowired
    private StockReservationRepository reservationRepository;

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final StockCommands commands = new StockCommands(merchant, store, product);

    private UUID skuA;
    private WalSlot wal;

    @BeforeEach
    void setUp() {
        skuA = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        wal = WalSlot.create(jdbc, "it_stock_replies");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    /** The outbox INSERTs of one command, with the changes of its transaction. */
    private record Published(List<JsonNode> replies, List<WalSlot.Change> transaction) {

        JsonNode only() {
            assertThat(replies).hasSize(1);
            return replies.get(0);
        }

        boolean changedBusinessTables() {
            return transaction.stream().anyMatch(change -> !change.isOutbox());
        }
    }

    /** Consumes the WAL; asserts every outbox INSERT has its DELETE in the same xid and validates each payload. */
    private Published published() {
        List<WalSlot.Change> changes = wal.changes();
        List<WalSlot.Change> inserts = changes.stream()
                .filter(c -> c.isOutbox() && c.op().equals("INSERT")).toList();
        Set<String> deleted = changes.stream().filter(c -> c.isOutbox() && c.op().equals("DELETE"))
                .map(c -> c.xid() + "/" + c.id()).collect(Collectors.toSet());
        inserts.forEach(insert -> assertThat(deleted).contains(insert.xid() + "/" + insert.id()));
        Set<String> xids = inserts.stream().map(WalSlot.Change::xid).collect(Collectors.toSet());
        List<JsonNode> replies = inserts.stream().map(insert -> {
            try {
                JsonNode payload = JSON.readTree(insert.payload());
                assertThat(payload.get("eventId").asText()).isEqualTo(insert.id());
                assertThat(ReplySchemas.validate(payload)).as(payload.toString()).isEmpty();
                return payload;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
        return new Published(replies, changes.stream().filter(c -> xids.contains(c.xid())).toList());
    }

    @Test
    @DisplayName("reserve → StockReserved in the transaction of the reservation; a duplicate → a second reply, no stock change")
    void reserveAndDuplicate() {
        ReserveStockCmd reserve = commands.reserve(UUID.randomUUID(), commands.line(UUID.randomUUID(), "SKU-A", 3));

        reserveService.reserve(reserve);
        Published first = published();
        reserveService.reserve(reserve);
        Published duplicate = published();

        assertThat(first.only().get("eventType").asText()).isEqualTo("StockReserved");
        assertThat(first.only().get("causationId").asText()).isEqualTo(reserve.getEventId().getValue().toString());
        assertThat(first.transaction()).anyMatch(c -> c.table().endsWith(".stock_reservations"));

        assertThat(duplicate.only().get("eventType").asText()).isEqualTo("StockReserved");
        assertThat(duplicate.only().get("eventId").asText()).isNotEqualTo(first.only().get("eventId").asText());
        assertThat(duplicate.only().get("causationId").asText()).isEqualTo(reserve.getEventId().getValue().toString());
        assertThat(duplicate.only().get("expiresAt").asText()).isEqualTo(first.only().get("expiresAt").asText());
        assertThat(duplicate.changedBusinessTables()).isFalse();
        assertThat(reserved(skuA)).isEqualTo(3);
    }

    @Test
    @DisplayName("rejected, confirmed, released and a tombstone each publish one valid reply")
    void eachReplyType() {
        UUID rejected = UUID.randomUUID();
        reserveService.reserve(commands.reserve(rejected, commands.line(UUID.randomUUID(), "SKU-A", 99)));
        assertThat(published().only().get("eventType").asText()).isEqualTo("StockReservationRejected");

        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 2)));
        published();
        confirmService.confirm(commands.confirm(order));
        Published confirmed = published();
        assertThat(confirmed.only().get("eventType").asText()).isEqualTo("StockReservationConfirmed");
        assertThat(confirmed.transaction()).anyMatch(c -> c.table().endsWith(".stock_reservation_orders"));

        releaseService.release(commands.release(order, true));
        JsonNode released = published().only();
        assertThat(released.get("eventType").asText()).isEqualTo("StockReservationReleased");
        assertThat(released.get("lines").get(0).get("status").asText()).isEqualTo("RETURNED");

        confirmService.confirm(commands.confirm(UUID.randomUUID()));
        JsonNode tombstone = published().only();
        assertThat(tombstone.get("eventType").asText()).isEqualTo("StockReservationConfirmationFailed");
        assertThat(tombstone.get("reason").asText()).isEqualTo("UNKNOWN_ORDER");
    }

    @Test
    @DisplayName("duplicate confirm (header CONFIRMED) and duplicate release (header CANCELLED) publish again, change nothing")
    void duplicateConfirmAndRelease() {
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 2)));
        confirmService.confirm(commands.confirm(order));
        published();

        var confirm = commands.confirm(order);
        confirmService.confirm(confirm);
        Published confirmAgain = published();
        assertThat(confirmAgain.only().get("eventType").asText()).isEqualTo("StockReservationConfirmed");
        assertThat(confirmAgain.only().get("causationId").asText()).isEqualTo(confirm.getEventId().getValue().toString());
        assertThat(confirmAgain.changedBusinessTables()).isFalse();

        releaseService.release(commands.release(order, true));
        published();
        var release = commands.release(order, false);
        releaseService.release(release);
        Published releaseAgain = published();
        assertThat(releaseAgain.only().get("eventType").asText()).isEqualTo("StockReservationReleased");
        assertThat(releaseAgain.only().get("restock").asBoolean()).isTrue(); // fixed by the first release
        assertThat(releaseAgain.only().get("causationId").asText()).isEqualTo(release.getEventId().getValue().toString());
        assertThat(releaseAgain.changedBusinessTables()).isFalse();
        assertThat(quantity(skuA)).isEqualTo(10);
    }

    @Test
    @DisplayName("contract errors publish nothing")
    void contractErrors() {
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        published();

        assertThatThrownBy(() -> reserveService.reserve(
                commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 2))))
                .isInstanceOf(InvalidStockCommandException.class);
        assertThatThrownBy(() -> releaseService.release(
                new StockCommands(merchant, UUID.randomUUID(), product).release(order, true)))
                .isInstanceOf(StockCommandOwnershipMismatchException.class);

        assertThat(published().replies()).isEmpty();
    }

    @Test
    @DisplayName("expiry of two orders on one inventory → one StockReservationExpired per order, causationId null")
    void expiry() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        reserveService.reserve(commands.reserve(first, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        reserveService.reserve(commands.reserve(second, commands.line(UUID.randomUUID(), "SKU-A", 2)));
        jdbc.update("UPDATE stock_reservations SET expires_at = now() - interval '1 minute'");
        published();

        List<StockReservation> due = tx.execute(st -> reservationRepository.getExpiredReservations(Instant.now(),
                ExpiryCursor.start(), 100));
        expireTransaction.execute(InventoryId.of(skuA), due);

        Published expired = published();
        assertThat(expired.replies()).hasSize(2);
        assertThat(expired.replies()).allSatisfy(reply -> {
            assertThat(reply.get("eventType").asText()).isEqualTo("StockReservationExpired");
            assertThat(reply.get("causationId").isNull()).isTrue();
            assertThat(reply.get("lines").get(0).get("sku").asText()).isEqualTo("SKU-A");
        });
        assertThat(expired.replies()).extracting(reply -> reply.get("orderId").asText())
                .containsExactlyInAnyOrder(first.toString(), second.toString());
    }
}
