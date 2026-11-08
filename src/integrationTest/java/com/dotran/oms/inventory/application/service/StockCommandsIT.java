package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;
import com.dotran.oms.inventory.application.repository.ExpiryCursor;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import com.dotran.oms.inventory.domain.exception.StockCommandOwnershipMismatchException;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.StockCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three stock commands and the expiry end to end on Postgres, one command per committed transaction. */
@DisplayName("Stock commands end to end (Postgres)")
class StockCommandsIT extends PostgresIT {

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
    private UUID skuB;

    @BeforeEach
    void rows() {
        skuA = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        skuB = insertInventory(merchant, store, product, "SKU-B", 5, 0, "ACTIVE");
    }

    private int movements(String type) {
        return count("SELECT count(*) FROM stock_movements WHERE type = ?", type);
    }

    private String header(UUID order) {
        return jdbc.queryForObject("SELECT status FROM stock_reservation_orders WHERE order_id = ?", String.class, order);
    }

    @Test
    @DisplayName("reserve → duplicate → confirm → release with restock: stock, statuses and one movement per step and line")
    void lifecycle() {
        UUID order = UUID.randomUUID();
        var reserve = commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 3),
                commands.line(UUID.randomUUID(), "SKU-B", 2));

        ReserveStockResult first = reserveService.reserve(reserve);
        ReserveStockResult duplicate = reserveService.reserve(reserve);

        assertThat(first).isInstanceOf(ReserveStockResult.Reserved.class);
        // The duplicate answers the stored lines; Postgres keeps microseconds, the first answer had nanoseconds.
        assertThat(duplicate).isInstanceOf(ReserveStockResult.Reserved.class);
        assertThat(((ReserveStockResult.Reserved) duplicate).lines()).hasSize(2);
        assertThat(reserved(skuA)).isEqualTo(3);
        assertThat(reserved(skuB)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM stock_reservations WHERE order_id = ?", order)).isEqualTo(2);
        assertThat(movements("RESERVE")).isEqualTo(2);
        assertThat(header(order)).isEqualTo("RESERVED");

        ConfirmStockReservationResult confirmed = confirmService.confirm(commands.confirm(order));
        ConfirmStockReservationResult confirmedAgain = confirmService.confirm(commands.confirm(order));

        assertThat(confirmed).isInstanceOf(ConfirmStockReservationResult.Confirmed.class);
        assertThat(confirmedAgain).isInstanceOf(ConfirmStockReservationResult.Confirmed.class);
        assertThat(quantity(skuA)).isEqualTo(7);
        assertThat(reserved(skuA)).isZero();
        assertThat(movements("SALE")).isEqualTo(2);
        assertThat(header(order)).isEqualTo("CONFIRMED");

        ReleaseStockReservationResult released = releaseService.release(commands.release(order, true));
        ReleaseStockReservationResult releasedAgain = releaseService.release(commands.release(order, false));

        assertThat(released.restock()).isTrue();
        assertThat(released.lines()).extracting(ReleaseStockReservationResult.Line::status)
                .containsOnly(ReservationStatus.RETURNED);
        assertThat(releasedAgain.restock()).isTrue(); // fixed by the first release
        assertThat(quantity(skuA)).isEqualTo(10);
        assertThat(quantity(skuB)).isEqualTo(5);
        assertThat(movements("RETURN")).isEqualTo(2);
        assertThat(header(order)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT restock FROM stock_reservation_orders WHERE order_id = ?",
                Boolean.class, order)).isTrue();
    }

    @Test
    @DisplayName("a rejected reserve holds nothing and a duplicate gets the stored reasons")
    void rejection() {
        jdbc.update("UPDATE inventories SET status = 'INACTIVE' WHERE id = ?", skuB);
        UUID order = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        var reserve = commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 3),
                commands.line(itemB, "SKU-B", 1), commands.line(UUID.randomUUID(), "SKU-X", 1));

        ReserveStockResult first = reserveService.reserve(reserve);
        ReserveStockResult duplicate = reserveService.reserve(reserve);

        assertThat(first).isInstanceOf(ReserveStockResult.Rejected.class);
        assertThat(((ReserveStockResult.Rejected) first).lines())
                .extracting(l -> l.reason())
                .containsExactly(LineRejectionReason.NOT_ORDERABLE, LineRejectionReason.UNKNOWN_SKU);
        assertThat(duplicate).isEqualTo(first);
        assertThat(reserved(skuA)).isZero();
        assertThat(count("SELECT count(*) FROM stock_reservations")).isZero();
        assertThat(count("SELECT count(*) FROM stock_movements")).isZero();
        assertThat(header(order)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("release before reserve leaves a tombstone: the late reserve is rejected ORDER_CLOSED")
    void releaseBeforeReserve() {
        UUID order = UUID.randomUUID();

        releaseService.release(commands.release(order, false));
        ReserveStockResult late = reserveService.reserve(
                commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));

        assertThat(late).isEqualTo(new ReserveStockResult.Rejected(OrderRejectionReason.ORDER_CLOSED, List.of()));
        assertThat(reserved(skuA)).isZero();
        assertThat(header(order)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("confirm before reserve: UNKNOWN_ORDER, then ORDER_CLOSED; the first release still stores restock")
    void confirmBeforeReserve() {
        UUID order = UUID.randomUUID();

        var first = confirmService.confirm(commands.confirm(order));
        var second = confirmService.confirm(commands.confirm(order));
        var release = releaseService.release(commands.release(order, true));

        assertThat(first).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(
                ConfirmStockReservationResult.Reason.UNKNOWN_ORDER, List.of()));
        assertThat(second).isEqualTo(new ConfirmStockReservationResult.ConfirmationFailed(
                ConfirmStockReservationResult.Reason.ORDER_CLOSED, List.of()));
        assertThat(release.restock()).isTrue();
        assertThat(jdbc.queryForObject("SELECT restock FROM stock_reservation_orders WHERE order_id = ?",
                Boolean.class, order)).isTrue();
    }

    @Test
    @DisplayName("contract errors write nothing: other lines for a reserved order, a command from another store")
    void contractErrors() {
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 1)));
        StockCommands otherStore = new StockCommands(merchant, UUID.randomUUID(), product);

        assertThatThrownBy(() -> reserveService.reserve(
                commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 2))))
                .isInstanceOf(InvalidStockCommandException.class);
        assertThatThrownBy(() -> releaseService.release(otherStore.release(order, true)))
                .isInstanceOf(StockCommandOwnershipMismatchException.class);

        assertThat(reserved(skuA)).isEqualTo(1);
        assertThat(movements("RESERVE")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM stock_reservation_orders")).isEqualTo(1);
        assertThat(header(order)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("expiry frees the hold with RESERVE_EXPIRE; a later confirm answers NOT_RESERVED, a release changes nothing")
    void expiry() {
        UUID order = UUID.randomUUID();
        reserveService.reserve(commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 4)));
        jdbc.update("UPDATE stock_reservations SET expires_at = now() - interval '1 minute' WHERE order_id = ?", order);

        List<StockReservation> due = tx.execute(st -> reservationRepository.getExpiredReservations(Instant.now(),
                ExpiryCursor.start(), 100));
        expireTransaction.execute(InventoryId.of(skuA), due);
        var confirm = confirmService.confirm(commands.confirm(order));
        var release = releaseService.release(commands.release(order, false));

        assertThat(reserved(skuA)).isZero();
        assertThat(movements("RESERVE_EXPIRE")).isEqualTo(1);
        assertThat(confirm).isInstanceOf(ConfirmStockReservationResult.ConfirmationFailed.class);
        assertThat(((ConfirmStockReservationResult.ConfirmationFailed) confirm).reason())
                .isEqualTo(ConfirmStockReservationResult.Reason.NOT_RESERVED);
        assertThat(release.lines()).extracting(ReleaseStockReservationResult.Line::status)
                .containsExactly(ReservationStatus.EXPIRED);
        assertThat(movements("RESERVE_RELEASE")).isZero();
        assertThat(quantity(skuA)).isEqualTo(10);
    }

    @Test
    @DisplayName("an INACTIVE row created by support is not reservable (BR-INV-08)")
    void supportRowNotOrderable() {
        insertInventory(merchant, store, product, "SKU-S", 50, 0, "INACTIVE");

        ReserveStockResult result = reserveService.reserve(
                commands.reserve(UUID.randomUUID(), commands.line(UUID.randomUUID(), "SKU-S", 1)));

        assertThat(result).isInstanceOf(ReserveStockResult.Rejected.class);
        assertThat(((ReserveStockResult.Rejected) result).lines()).extracting(l -> l.reason())
                .containsExactly(LineRejectionReason.NOT_ORDERABLE);
    }
}
