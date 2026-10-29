-- Per-product ordering gate for catalog events (ProductCreated = version 0, ProductUpdated >= 1).
-- One row per product; an event is applied only if its version is newer and merchant/store match.
CREATE TABLE IF NOT EXISTS product_catalog_versions
(
    product_id   UUID PRIMARY KEY,
    merchant_id  UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    version      BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- stores-service accepts SKU codes up to 100 characters (metadata-only change, no table rewrite).
ALTER TABLE inventories ALTER COLUMN sku TYPE VARCHAR(100);

-- Load all inventory rows of a product regardless of store (ownership check in the catalog sync).
CREATE INDEX IF NOT EXISTS idx_inventory_product
    ON inventories (store_product_id);
