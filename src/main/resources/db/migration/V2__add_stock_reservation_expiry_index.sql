-- Supports the expiry sweep: keyset pages of RESERVED reservations in (expires_at, id) order.
-- Partial, so it only holds active reservations and stays small as finished ones accumulate.
-- The query must keep status = 'RESERVED' as a literal for the planner to use this index.
CREATE INDEX idx_stock_reservation_expiry
    ON stock_reservations (expires_at, id)
    WHERE status = 'RESERVED';
