-- Outbox of the stock replies (ADR 0001, ADR 0006). Debezium (connector inventory-outbox-cdc, development repo) reads
-- the INSERTs from the WAL and routes them by event_type (topic), aggregate_id (key) and id (header id = eventId).
-- Rows are deleted in the same transaction as they are inserted, so the table is always empty (open question 19,
-- decided 2026-11-08): no status column, no index beyond the primary key.
CREATE TABLE IF NOT EXISTS outbox_events
(
    id                  UUID PRIMARY KEY,
    merchant_id         UUID         NOT NULL,
    aggregate_type      VARCHAR(100) NOT NULL,
    aggregate_id        UUID         NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    payload             JSONB        NOT NULL,
    -- Propagated MDC keys of the writing thread (ADR 0004), Kafka header correlationContext.
    correlation_context JSONB        NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
