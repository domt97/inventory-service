-- Stock commands (ADR 0006): one header row per order makes ReserveStock / ConfirmStockReservation /
-- ReleaseStockReservation idempotent and absorbs commands that arrive out of order (a header closed before any
-- reserve is a tombstone). Every command locks this row first.
-- Pre-deploy check (expected 0 rows; V4 fails on duplicates):
--   SELECT order_id, order_item_id, count(*) FROM stock_reservations GROUP BY 1, 2 HAVING count(*) > 1;
CREATE TABLE IF NOT EXISTS stock_reservation_orders
(
    order_id     UUID PRIMARY KEY,
    merchant_id  UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    status       VARCHAR(30) NOT NULL,
    -- Fixed by the first ReleaseStockReservation (BR-ORD-09); null until then.
    restock      BOOLEAN,
    -- Rejection of the reserve (orderReason, reason per rejected line), so a duplicate reserve gets the same answer.
    rejection    JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_stock_reservation_order_status
        CHECK (status IN ('RESERVED', 'REJECTED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_stock_reservation_order_rejection
        CHECK (status <> 'REJECTED' OR rejection IS NOT NULL),
    CONSTRAINT ck_stock_reservation_order_restock
        CHECK (restock IS NULL OR status IN ('REJECTED', 'CANCELLED'))
);

-- One reservation per order line (BR-INV-07). Its index starts with order_id, so the plain index is redundant.
ALTER TABLE stock_reservations
    ADD CONSTRAINT uk_stock_reservation_order_item UNIQUE (order_id, order_item_id);
DROP INDEX IF EXISTS idx_stock_reservation_order;

-- Movements written for a reservation (RESERVE, SALE, RESERVE_RELEASE, RESERVE_EXPIRE, RETURN): at most one per
-- (reservation, type). Manual adjustments keep reservation_id NULL.
ALTER TABLE stock_movements ADD COLUMN IF NOT EXISTS reservation_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_movement_reservation_type
    ON stock_movements (reservation_id, type)
    WHERE reservation_id IS NOT NULL;
