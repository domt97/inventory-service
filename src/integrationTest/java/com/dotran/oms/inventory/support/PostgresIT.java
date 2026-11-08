package com.dotran.oms.inventory.support;

import com.dotran.oms.inventory.application.config.ReservationProperties;
import com.dotran.oms.inventory.application.service.ConfirmStockReservationService;
import com.dotran.oms.inventory.application.service.ExpireByInventoryTransaction;
import com.dotran.oms.inventory.application.service.ReleaseStockReservationService;
import com.dotran.oms.inventory.application.service.ReserveStockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.util.UUID;

/**
 * Base of the integration tests: one Postgres 16 container for the whole run, Flyway V1..Vn into schema
 * {@code inventory}, the JPA slice plus the persistence adapters and the stock command services. No Kafka, SQS or
 * DynamoDB. Tests run without a surrounding transaction (each service / {@link #tx} call commits), and every table but
 * the Flyway history is truncated before each test. Never assert generated ids: the sequences are not reset.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false",
        "spring.flyway.schemas=inventory",
        "spring.flyway.default-schema=inventory"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(PostgresIT.Config.class)
public abstract class PostgresIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        // Started once per JVM, stopped by Testcontainers' Ryuk at the end of the run.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=inventory";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // application.yaml sets its own Flyway connection: override all three.
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE TABLE stock_movements, stock_reservations, stock_reservation_orders, inventories, "
                + "product_catalog_versions");
    }

    /** Inserts an inventory row and returns its id. */
    protected UUID insertInventory(UUID merchantId, UUID storeId, UUID productId, String sku, long quantity,
                                   long reserved, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventories (id, merchant_id, store_id, store_product_id, sku, quantity, reserved_quantity, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, merchantId, storeId, productId, sku, quantity, reserved, status);
        return id;
    }

    protected long quantity(UUID inventoryId) {
        return jdbc.queryForObject("SELECT quantity FROM inventories WHERE id = ?", Long.class, inventoryId);
    }

    protected long reserved(UUID inventoryId) {
        return jdbc.queryForObject("SELECT reserved_quantity FROM inventories WHERE id = ?", Long.class, inventoryId);
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @TestConfiguration
    @ComponentScan(basePackages = {
            "com.dotran.oms.inventory.infrastructure.persistence",
            "com.dotran.oms.inventory.infrastructure.mapper",
            "com.dotran.oms.inventory.common.mapper"
    })
    @EnableConfigurationProperties(ReservationProperties.class)
    @Import({ReserveStockService.class, ConfirmStockReservationService.class, ReleaseStockReservationService.class,
            ExpireByInventoryTransaction.class})
    static class Config {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TransactionTemplate transactionTemplate(org.springframework.transaction.PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
