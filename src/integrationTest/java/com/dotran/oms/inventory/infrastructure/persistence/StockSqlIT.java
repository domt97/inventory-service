package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.repository.StockState;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import com.dotran.oms.inventory.support.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native SQL, constraints and JSONB of the stock commands against a real Postgres (Flyway V1..V4). */
@DisplayName("Stock command SQL (Postgres)")
class StockSqlIT extends PostgresIT {

    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private StockReservationRepository reservationRepository;
    @Autowired
    private StockReservationOrderRepository headerRepository;
    @Autowired
    private StockMovementRepository movementRepository;
    @Autowired
    private TransactionTimeoutRepository timeoutRepository;

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    private UUID active;
    private UUID inactive;

    @BeforeEach
    void rows() {
        active = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        inactive = insertInventory(merchant, store, product, "SKU-I", 10, 0, "INACTIVE");
    }

    private long insertReservation(UUID orderId, UUID orderItemId, UUID inventoryId, long quantity, String status) {
        return jdbc.queryForObject("""
                INSERT INTO stock_reservations (merchant_id, store_id, inventory_id, order_id, order_item_id, quantity,
                                                status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, merchant, store, inventoryId, orderId, orderItemId, quantity, status,
                Instant.parse("2026-11-07T10:15:00.123456Z").atOffset(ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("V4 constraints")
    class Constraints {

        @Test
        @DisplayName("one reservation per (order, order item)")
        void uniqueOrderItem() {
            UUID order = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            insertReservation(order, item, active, 1, "RESERVED");

            assertThatThrownBy(() -> insertReservation(order, item, active, 1, "RESERVED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("header: REJECTED needs a rejection; restock only on REJECTED / CANCELLED; known statuses only")
        void headerChecks() {
            String insert = "INSERT INTO stock_reservation_orders (order_id, merchant_id, store_id, status, restock) "
                    + "VALUES (?, ?, ?, ?, ?)";
            assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), merchant, store, "REJECTED", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), merchant, store, "RESERVED", true))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), merchant, store, "OPEN", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("guarded inventory SQL")
    class Inventories {

        @Test
        @DisplayName("reserve: refused for INACTIVE, another merchant/store or short stock; applied otherwise")
        void guardedReserve() {
            MerchantId m = MerchantId.of(merchant);
            StoreId s = StoreId.of(store);
            Integer refusedInactive = tx.execute(st -> inventoryRepository.reserve(InventoryId.of(inactive), m, s, 1));
            Integer refusedMerchant = tx.execute(st -> inventoryRepository.reserve(InventoryId.of(active),
                    MerchantId.of(UUID.randomUUID()), s, 1));
            Integer refusedStore = tx.execute(st -> inventoryRepository.reserve(InventoryId.of(active), m,
                    StoreId.of(UUID.randomUUID()), 1));
            Integer refusedStock = tx.execute(st -> inventoryRepository.reserve(InventoryId.of(active), m, s, 11));
            Integer applied = tx.execute(st -> inventoryRepository.reserve(InventoryId.of(active), m, s, 10));

            assertThat(List.of(refusedInactive, refusedMerchant, refusedStore, refusedStock)).containsOnly(0);
            assertThat(applied).isEqualTo(1);
            assertThat(reserved(active)).isEqualTo(10);
            assertThat(reserved(inactive)).isZero();
        }

        @Test
        @DisplayName("loads rows by merchant, store and SKUs; reads status and available past the persistence context")
        void loadAndStockStates() {
            insertInventory(UUID.randomUUID(), store, product, "SKU-B", 1, 0, "ACTIVE"); // other merchant, same store
            jdbc.update("UPDATE inventories SET reserved_quantity = 4 WHERE id = ?", active);

            var rows = tx.execute(st -> inventoryRepository.getByMerchantStoreAndSkus(MerchantId.of(merchant),
                    StoreId.of(store), List.of(SKU.of("SKU-A"), SKU.of("SKU-B"), SKU.of("SKU-I"))));
            List<StockState> states = tx.execute(st -> inventoryRepository.getStockStates(
                    List.of(InventoryId.of(active), InventoryId.of(inactive))));

            assertThat(rows).extracting(r -> r.getSku().getValue()).containsExactlyInAnyOrder("SKU-A", "SKU-I");
            assertThat(states).containsExactlyInAnyOrder(
                    new StockState(InventoryId.of(active), InventoryStatus.ACTIVE, 6),
                    new StockState(InventoryId.of(inactive), InventoryStatus.INACTIVE, 10));
        }
    }

    @Nested
    @DisplayName("order header")
    class Header {

        private StockReservationOrder header(UUID order, OrderReservationStatus status, Boolean restock,
                                             ReservationRejection rejection) {
            return new StockReservationOrder(OrderId.of(order), MerchantId.of(merchant), StoreId.of(store), status,
                    restock, rejection);
        }

        @Test
        @DisplayName("insertIfAbsent creates once; lockForUpdate is scoped by merchant and store")
        void insertAndScopedLock() {
            UUID order = UUID.randomUUID();

            Boolean first = tx.execute(st -> headerRepository.insertIfAbsent(
                    header(order, OrderReservationStatus.CANCELLED, null, null)));
            Boolean second = tx.execute(st -> headerRepository.insertIfAbsent(
                    header(order, OrderReservationStatus.RESERVED, true, null)));
            Optional<StockReservationOrder> own = tx.execute(st -> headerRepository.lockForUpdate(OrderId.of(order),
                    MerchantId.of(merchant), StoreId.of(store)));
            Optional<StockReservationOrder> otherStore = tx.execute(st -> headerRepository.lockForUpdate(
                    OrderId.of(order), MerchantId.of(merchant), StoreId.of(UUID.randomUUID())));

            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(own).contains(header(order, OrderReservationStatus.CANCELLED, null, null));
            assertThat(otherStore).isEmpty();
        }

        @Test
        @DisplayName("update writes restock and a JSONB rejection that reads back equal; a stale expected status updates 0")
        void updateRoundTrip() {
            UUID order = UUID.randomUUID();
            ReservationRejection rejection = ReservationRejection.ofLines(List.of(
                    new ReservationRejection.Line(OrderItemId.of(UUID.randomUUID()), SKU.of("SKU-A"),
                            LineRejectionReason.INSUFFICIENT_STOCK)));
            tx.execute(st -> headerRepository.insertIfAbsent(header(order, OrderReservationStatus.RESERVED, null, null)));

            Integer rejected = tx.execute(st -> headerRepository.update(
                    header(order, OrderReservationStatus.REJECTED, null, rejection), OrderReservationStatus.RESERVED));
            Integer stale = tx.execute(st -> headerRepository.update(
                    header(order, OrderReservationStatus.CANCELLED, true, null), OrderReservationStatus.RESERVED));
            Integer restock = tx.execute(st -> headerRepository.update(
                    header(order, OrderReservationStatus.REJECTED, false, rejection), OrderReservationStatus.REJECTED));
            Optional<StockReservationOrder> read = tx.execute(st -> headerRepository.lockForUpdate(OrderId.of(order),
                    MerchantId.of(merchant), StoreId.of(store)));

            assertThat(List.of(rejected, stale, restock)).containsExactly(1, 0, 1);
            assertThat(read).contains(header(order, OrderReservationStatus.REJECTED, false, rejection));
            assertThat(jdbc.queryForObject("SELECT jsonb_typeof(rejection) FROM stock_reservation_orders "
                    + "WHERE order_id = ?", String.class, order)).isEqualTo("object");
        }

        @Test
        @DisplayName("an order rejection is stored too")
        void orderRejection() {
            UUID order = UUID.randomUUID();
            ReservationRejection rejection = ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND);
            tx.execute(st -> headerRepository.insertIfAbsent(header(order, OrderReservationStatus.RESERVED, null, null)));
            tx.execute(st -> headerRepository.update(header(order, OrderReservationStatus.REJECTED, null, rejection),
                    OrderReservationStatus.RESERVED));

            assertThat(tx.execute(st -> headerRepository.lockForUpdate(OrderId.of(order), MerchantId.of(merchant),
                    StoreId.of(store)))).map(StockReservationOrder::rejection).contains(rejection);
        }

        @Test
        @DisplayName("needs a surrounding transaction (MANDATORY)")
        void mandatory() {
            assertThatThrownBy(() -> headerRepository.lockForUpdate(OrderId.of(UUID.randomUUID()),
                    MerchantId.of(merchant), StoreId.of(store)))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    @Nested
    @DisplayName("reservations and movements")
    class ReservationsAndMovements {

        @Test
        @DisplayName("line views join the SKU and product and keep microseconds of expires_at")
        void lineViews() {
            UUID order = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            insertReservation(order, item, active, 3, "RESERVED");
            insertReservation(UUID.randomUUID(), UUID.randomUUID(), active, 1, "RESERVED"); // other order

            List<ReservationLineView> views = tx.execute(st -> reservationRepository.getLineViews(
                    MerchantId.of(merchant), StoreId.of(store), OrderId.of(order)));
            List<ReservationLineView> otherStore = tx.execute(st -> reservationRepository.getLineViews(
                    MerchantId.of(merchant), StoreId.of(UUID.randomUUID()), OrderId.of(order)));

            assertThat(views).singleElement().satisfies(v -> {
                assertThat(v.orderItemId()).isEqualTo(OrderItemId.of(item));
                assertThat(v.sku()).isEqualTo(SKU.of("SKU-A"));
                assertThat(v.productId().getValue()).isEqualTo(product);
                assertThat(v.quantity()).isEqualTo(3);
                assertThat(v.status()).isEqualTo(ReservationStatus.RESERVED);
                assertThat(v.expiresAt()).isEqualTo(Instant.parse("2026-11-07T10:15:00.123456Z"));
            });
            assertThat(otherStore).isEmpty();
        }

        @Test
        @DisplayName("insertForReservations: only rows in the required status, ORDER_ITEM reference, at most once per (reservation, type)")
        void movements() {
            UUID order = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            long reserved = insertReservation(order, item, active, 3, "RESERVED");
            long expired = insertReservation(order, UUID.randomUUID(), active, 2, "EXPIRED");
            List<StockReservationId> ids = List.of(StockReservationId.of(reserved), StockReservationId.of(expired));

            Integer first = tx.execute(st -> movementRepository.insertForReservations(ids, StockMovementType.RESERVE,
                    ReservationStatus.RESERVED, null));
            Integer again = tx.execute(st -> movementRepository.insertForReservations(ids, StockMovementType.RESERVE,
                    ReservationStatus.RESERVED, null));
            Integer otherType = tx.execute(st -> movementRepository.insertForReservations(ids,
                    StockMovementType.RESERVE_RELEASE, ReservationStatus.RESERVED, "CUSTOMER_CANCELLED"));

            assertThat(List.of(first, again, otherType)).containsExactly(1, 0, 1);
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT type, quantity, reference_type, reference_id, reservation_id, reason, inventory_id, merchant_id
                    FROM stock_movements WHERE type = 'RESERVE'
                    """);
            assertThat(row).containsEntry("quantity", 3L)
                    .containsEntry("reference_type", "ORDER_ITEM")
                    .containsEntry("reference_id", item)
                    .containsEntry("reservation_id", reserved)
                    .containsEntry("inventory_id", active)
                    .containsEntry("merchant_id", merchant);
            assertThat(row.get("reason")).isNull();
            assertThat(jdbc.queryForObject("SELECT reason FROM stock_movements WHERE type = 'RESERVE_RELEASE'",
                    String.class)).isEqualTo("CUSTOMER_CANCELLED");
        }

        @Test
        @DisplayName("guarded status transitions report only the rows that moved")
        void transitions() {
            UUID order = UUID.randomUUID();
            long a = insertReservation(order, UUID.randomUUID(), active, 1, "RESERVED");
            long b = insertReservation(order, UUID.randomUUID(), active, 1, "CONFIRMED");

            var released = tx.execute(st -> reservationRepository.markReleased(
                    List.of(StockReservationId.of(a), StockReservationId.of(b))));
            var returned = tx.execute(st -> reservationRepository.markReturned(
                    List.of(StockReservationId.of(a), StockReservationId.of(b))));

            assertThat(released).containsExactly(StockReservationId.of(a));
            assertThat(returned).containsExactly(StockReservationId.of(b));
        }
    }

    @Test
    @DisplayName("applyLocal sets lock_timeout / statement_timeout for the transaction only")
    void localTimeouts() {
        String inside = tx.execute(st -> {
            timeoutRepository.applyLocal(Duration.ofMillis(1500), Duration.ofSeconds(4));
            return jdbc.queryForObject("SELECT current_setting('lock_timeout') || '/' || "
                    + "current_setting('statement_timeout')", String.class);
        });
        String after = jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);

        assertThat(inside).isEqualTo("1500ms/4s");
        assertThat(after).isEqualTo("0");
    }
}
