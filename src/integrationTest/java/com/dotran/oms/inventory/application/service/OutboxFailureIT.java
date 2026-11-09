package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.event.OutboxMessage;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.infrastructure.persistence.OutboxEventPersistenceAdapter;
import com.dotran.oms.inventory.support.PostgresIT;
import com.dotran.oms.inventory.support.StockCommands;
import com.dotran.oms.inventory.support.WalSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failing outbox write rolls back the whole decision: no stock, header or movement change, nothing in the WAL.
 * Lives in application.service (not a package PostgresIT scans) so its throwing {@code @Primary} bean stays in this
 * class's own context.
 */
@DisplayName("Outbox failure rolls back the decision (Postgres)")
class OutboxFailureIT extends PostgresIT {

    @TestConfiguration
    static class FailingOutbox {

        /** Writes the real rows, then fails, as a database error after the insert would. */
        @Bean
        @Primary
        OutboxEventRepository failingOutbox(OutboxEventPersistenceAdapter real) {
            return (List<OutboxMessage> messages) -> {
                real.saveAll(messages);
                throw new IllegalStateException("outbox failure (test)");
            };
        }
    }

    @Autowired
    private ReserveStockService reserveService;

    private final UUID merchant = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final StockCommands commands = new StockCommands(merchant, store, product);

    private UUID skuA;
    private WalSlot wal;

    @BeforeEach
    void setUp() {
        skuA = insertInventory(merchant, store, product, "SKU-A", 10, 0, "ACTIVE");
        wal = WalSlot.create(jdbc, "it_outbox_failure");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    @Test
    @DisplayName("reserve whose reply cannot be written changes nothing")
    void reserveRollsBack() {
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> reserveService.reserve(
                commands.reserve(order, commands.line(UUID.randomUUID(), "SKU-A", 3))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reserved(skuA)).isZero();
        assertThat(count("SELECT count(*) FROM stock_reservation_orders")).isZero();
        assertThat(count("SELECT count(*) FROM stock_reservations")).isZero();
        assertThat(count("SELECT count(*) FROM stock_movements")).isZero();
        assertThat(wal.changes()).isEmpty();
    }
}
