package io.primelogiclab.svcmon.hub.secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

import io.primelogiclab.svcmon.hub.model.AlertType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The secrets as they sit in the database, below the repositories: for sealing the ones stored before the hub
 * encrypted them, re-sealing under a new key, counting, and removing the ones no key opens. Only the config column
 * of alert_channels and checks is read or written, and only the values {@link SecretFields} names change.
 */
public final class StoredSecrets {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final SecretBox box;

    public StoredSecrets(DataSource ds, JsonMapper json, SecretBox box) {
        this.jdbc = JdbcClient.create(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        this.json = json;
        this.box = box;
    }

    /**
     * How the stored secrets stand. {@code unreadable} names the channels and checks that hold a value no key in the
     * file opens; {@code keyIds} are the keys the readable sealed values use.
     */
    public record Counts(int sealed, int plain, int unreadable, Set<String> keyIds, List<String> unreadableIn) {
    }

    /** Values encrypted at all, read or not: what a missing key file would lose. Needs no key. */
    public int countSealed() {
        int[] n = {0};
        forEachRow(v -> {
            if (SecretBox.isSealed(v)) {
                n[0]++;
            }
            return v;
        }, false);
        return n[0];
    }

    public Counts count() {
        int[] n = new int[3];
        Set<String> keyIds = new TreeSet<>();
        List<String> unreadableIn = new ArrayList<>();
        for (Row row : rows()) {
            int before = n[2];
            row.apply(v -> {
                if (v.isEmpty()) {
                    return v;
                }
                if (!SecretBox.isSealed(v)) {
                    n[1]++;
                } else if (box.opens(v)) {
                    n[0]++;
                    keyIds.add(SecretBox.keyId(v));
                } else {
                    n[2]++;
                }
                return v;
            });
            if (n[2] > before) {
                unreadableIn.add(row.label());
            }
        }
        return new Counts(n[0], n[1], n[2], keyIds, unreadableIn);
    }

    /**
     * Seals every value that is plain or sealed with a key other than the current one, in one transaction, and returns
     * how many changed. A value no key opens stops it before anything is written.
     */
    public int sealAll() {
        String current = box.keys().ring().current();
        int[] n = {0};
        tx.executeWithoutResult(status -> forEachRow(v -> {
            if (v.isEmpty() || SecretBox.isSealed(v) && SecretBox.keyId(v).equals(current)) {
                return v;
            }
            n[0]++;
            return box.seal(box.open(v));
        }, true));
        return n[0];
    }

    /**
     * Removes the values no key opens, in one transaction: a channel loses that setting (or header) and is switched
     * off, a check loses that header. Returns what changed, by name, for the person to enter again.
     */
    public List<String> forgetUnreadable() {
        List<String> changed = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (Row row : rows()) {
                Map<String, Object> config = row.apply(v -> box.opens(v) ? v : null);
                List<String> lost = new ArrayList<>();
                prune(config, lost, "");
                if (lost.isEmpty()) {
                    continue;
                }
                String sql = row.channel()
                        ? "UPDATE alert_channels SET config = :config, enabled = 0 WHERE id = :id"
                        : "UPDATE checks SET config = :config WHERE id = :id";
                jdbc.sql(sql).param("config", json.writeValueAsString(config)).param("id", row.id()).update();
                changed.add(row.label() + ": " + String.join(", ", lost) + (row.channel() ? " (switched off)" : ""));
            }
        });
        return changed;
    }

    /**
     * Rebuilds the database file and empties its write-ahead log. SQLite leaves a replaced value in the page's free
     * space and in old log frames, so without this a copy of the files would still hold what was just sealed over.
     */
    public void compact() {
        jdbc.sql("VACUUM").update();
        jdbc.sql("PRAGMA wal_checkpoint(TRUNCATE)").query().listOfRows();
    }

    /** Drops the entries set to null above, naming each: a setting by its name, a header as "header X". */
    @SuppressWarnings("unchecked")
    private static void prune(Map<String, Object> config, List<String> lost, String prefix) {
        for (var it = config.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> e = it.next();
            if (e.getValue() == null) {
                lost.add(prefix + e.getKey());
                it.remove();
            } else if (prefix.isEmpty() && e.getKey().equals("headers") && e.getValue() instanceof Map<?, ?> m) {
                prune((Map<String, Object>) m, lost, "header ");
            }
        }
    }

    private void forEachRow(UnaryOperator<String> f, boolean write) {
        for (Row row : rows()) {
            Map<String, Object> config = row.apply(f);
            if (write && !config.equals(row.config())) {
                String table = row.channel() ? "alert_channels" : "checks";
                jdbc.sql("UPDATE " + table + " SET config = :config WHERE id = :id")
                        .param("config", json.writeValueAsString(config))
                        .param("id", row.id())
                        .update();
            }
        }
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        jdbc.sql("SELECT id, name, type, config FROM alert_channels ORDER BY name")
                .query((rs, i) -> new Row(true, rs.getString("id"), rs.getString("name"),
                        AlertType.valueOf(rs.getString("type")), json.readValue(rs.getString("config"), MAP_TYPE)))
                .list().forEach(rows::add);
        jdbc.sql("SELECT id, name, config FROM checks ORDER BY name")
                .query((rs, i) -> new Row(false, rs.getString("id"), rs.getString("name"), null,
                        json.readValue(rs.getString("config"), MAP_TYPE)))
                .list().forEach(rows::add);
        return rows;
    }

    private record Row(boolean channel, String id, String name, AlertType type, Map<String, Object> config) {

        Map<String, Object> apply(UnaryOperator<String> f) {
            return channel ? SecretFields.channel(type, config, f) : SecretFields.check(config, f);
        }

        String label() {
            return (channel ? "alert channel '" : "check '") + name + "'";
        }
    }
}
