package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.inventory.application.event.OutboxMessage;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inserts the outbox rows and deletes them again in the same transaction (open question 19): Debezium publishes the
 * INSERTs from the WAL, the table stays empty. Native SQL binds {@code id = eventId} (no JPA id generation, cf.
 * stores-service open question 45). JdbcTemplate joins the JPA transaction (JpaTransactionManager exposes its
 * connection), so the rows commit or roll back with the stock change.
 */
@PersistenceAdapter
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPersistenceAdapter implements OutboxEventRepository {

    private static final String INSERT = """
            INSERT INTO outbox_events (id, merchant_id, aggregate_type, aggregate_id, event_type, payload,
                                       correlation_context, created_at)
            VALUES (:id, :merchantId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb),
                    CAST(:correlation AS jsonb), CURRENT_TIMESTAMP)
            """;
    private static final String DELETE = "DELETE FROM outbox_events WHERE id IN (:ids)";

    private final NamedParameterJdbcTemplate jdbc;
    private final OutboxPayloadSerializer serializer;
    private final MdcPropagation mdcPropagation;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveAll(List<OutboxMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        // Taken on the writing thread (ADR 0004): the command's or the expiry tick's requestId.
        String correlation = serializer.toJson(mdcPropagation.capture());
        SqlParameterSource[] rows = messages.stream()
                .map(message -> new MapSqlParameterSource()
                        .addValue("id", message.eventId())
                        .addValue("merchantId", message.merchantId())
                        .addValue("aggregateType", message.aggregateType())
                        .addValue("aggregateId", message.aggregateId())
                        .addValue("eventType", message.eventType())
                        .addValue("payload", serializer.toJson(message.payload()))
                        .addValue("correlation", correlation))
                .toArray(SqlParameterSource[]::new);

        int[] inserted = jdbc.batchUpdate(INSERT, rows);
        for (int count : inserted) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("Outbox insert wrote " + count + " rows");
            }
        }
        List<UUID> ids = messages.stream().map(OutboxMessage::eventId).toList();
        int deleted = jdbc.update(DELETE, Map.of("ids", ids));
        if (deleted != ids.size()) {
            throw new IllegalStateException("Outbox delete removed " + deleted + " of " + ids.size() + " rows");
        }

        // "Queued", not "published": the record exists only once the transaction commits.
        messages.forEach(message -> log.info("Stock reply queued: eventType={} eventId={} orderId={} causationId={}",
                message.eventType(), message.eventId(), message.aggregateId(), message.payload().causationId()));
    }
}
