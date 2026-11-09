package com.dotran.oms.inventory.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code test_decoding} logical replication slot: shows the committed row changes exactly as a CDC connector would
 * read them from the WAL (rolled-back transactions never appear). The outbox table is always empty after commit, so
 * the WAL is the only place to see what would be published.
 *
 * <p>Create it after the base class TRUNCATE (in a test's {@code @BeforeEach}) and {@link #drop()} it in
 * {@code @AfterEach}: the Postgres container is shared by every IT class of the run.
 */
public final class WalSlot {

    private static final Pattern CHANGE = Pattern.compile("^table (\\S+): (INSERT|UPDATE|DELETE): (.*)$");
    private static final Pattern ID = Pattern.compile("\\bid\\[uuid\\]:'([0-9a-f-]+)'");
    private static final Pattern PAYLOAD = Pattern.compile("\\bpayload\\[jsonb\\]:'((?:[^']|'')*)'");
    private static final Pattern CORRELATION = Pattern.compile("\\bcorrelation_context\\[jsonb\\]:'((?:[^']|'')*)'");

    private final JdbcTemplate jdbc;
    private final String name;

    private WalSlot(JdbcTemplate jdbc, String name) {
        this.jdbc = jdbc;
        this.name = name;
    }

    /** @param name a plain identifier (letters, digits, underscore) */
    public static WalSlot create(JdbcTemplate jdbc, String name) {
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Slot name " + name);
        }
        dropIfExists(jdbc, name);
        // Slot creation waits for transactions still running in other sessions: bound it, on one connection.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET statement_timeout = '10s'");
                try {
                    statement.execute("SELECT pg_create_logical_replication_slot('" + name + "', 'test_decoding')");
                } finally {
                    statement.execute("SET statement_timeout = 0");
                }
            }
            return null;
        });
        return new WalSlot(jdbc, name);
    }

    private static void dropIfExists(JdbcTemplate jdbc, String name) {
        jdbc.queryForList("SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = ?",
                name);
    }

    public void drop() {
        dropIfExists(jdbc, name);
    }

    /** Consumes and returns the committed row changes since the last call. */
    public List<Change> changes() {
        return jdbc.query("SELECT xid::text AS xid, data FROM pg_logical_slot_get_changes(?, NULL, NULL, "
                        + "'include-xids', '1', 'skip-empty-xacts', '1')",
                        (rs, i) -> Change.parse(rs.getString("xid"), rs.getString("data")), name)
                .stream()
                .filter(change -> change != null)
                .toList();
    }

    /** One row change ({@code BEGIN}/{@code COMMIT} lines are skipped). */
    public record Change(String xid, String table, String op, String data) {

        static Change parse(String xid, String data) {
            Matcher m = CHANGE.matcher(data);
            return m.matches() ? new Change(xid, m.group(1), m.group(2), m.group(3)) : null;
        }

        public boolean isOutbox() {
            return table.endsWith(".outbox_events");
        }

        public String id() {
            return group(ID);
        }

        /** The JSONB payload text of an outbox INSERT. */
        public String payload() {
            return group(PAYLOAD);
        }

        public String correlation() {
            return group(CORRELATION);
        }

        private String group(Pattern pattern) {
            Matcher m = pattern.matcher(data);
            return m.find() ? m.group(1).replace("''", "'") : null;
        }
    }
}
