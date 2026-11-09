package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.application.event.OutboxMessage;

import java.util.List;

/**
 * Writes outbox messages in the caller's transaction (ADR 0001). Rows are deleted in the same transaction: Debezium
 * publishes them from the WAL, so the table stays empty (open question 19). Empty list → nothing.
 */
public interface OutboxEventRepository {

    void saveAll(List<OutboxMessage> messages);
}
