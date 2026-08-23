CREATE TABLE IF NOT EXISTS inventories
(
    id                 UUID PRIMARY KEY,

    tenant_id          UUID NOT NULL,
    store_id           UUID NOT NULL,

    store_product_id   UUID NOT NULL,
    sku_id             UUID NOT NULL,

    quantity           BIGINT NOT NULL DEFAULT 0,
    reserved_quantity  BIGINT NOT NULL DEFAULT 0,

    status             VARCHAR(30) NOT NULL,

    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL,

    CONSTRAINT uk_inventory_store_sku
        UNIQUE (store_id, sku_id)
);

CREATE INDEX idx_inventory_store
    ON inventories (store_id);

CREATE INDEX idx_inventory_sku
    ON inventories (sku_id);

CREATE INDEX idx_inventory_store_product
    ON inventories (store_id, store_product_id);


CREATE SEQUENCE IF NOT EXISTS stock_reservations_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 20;

CREATE TABLE IF NOT EXISTS stock_reservations
(
    id              BIGINT PRIMARY KEY DEFAULT nextval('stock_reservations_seq'),

    tenant_id       UUID NOT NULL,
    store_id        UUID NOT NULL,

    inventory_id    UUID NOT NULL,

    order_id        UUID NOT NULL,
    order_item_id   UUID NOT NULL,

    quantity        BIGINT NOT NULL,

    status          VARCHAR(30) NOT NULL,

    expires_at      TIMESTAMP,

    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_reservation_order
    ON stock_reservations (order_id);

CREATE INDEX idx_stock_reservation_inventory
    ON stock_reservations (inventory_id);

CREATE INDEX idx_stock_reservation_status
    ON stock_reservations (status);


CREATE SEQUENCE IF NOT EXISTS stock_movements_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 20;

CREATE TABLE stock_movements
(
    id              BIGINT PRIMARY KEY DEFAULT nextval('stock_movements_seq'),

    tenant_id       UUID NOT NULL,
    store_id        UUID NOT NULL,

    inventory_id    UUID NOT NULL,

    type            VARCHAR(30) NOT NULL,

    quantity        BIGINT NOT NULL,

    reference_type  VARCHAR(50),
    reference_id    UUID,

    reason          VARCHAR(255),

    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_movement_inventory
    ON stock_movements (inventory_id);

CREATE INDEX idx_stock_movement_reference
    ON stock_movements (reference_type, reference_id);

CREATE INDEX idx_stock_movement_created_at
    ON stock_movements (created_at);