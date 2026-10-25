package io.primelogiclab.svcmon.hub.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckSource;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class CheckRepo {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<CheckDef> mapper;

    public CheckRepo(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new CheckDef(
                rs.getString("id"),
                rs.getString("name"),
                CheckType.valueOf(rs.getString("type")),
                rs.getString("target"),
                json.readValue(rs.getString("config"), MAP_TYPE),
                rs.getInt("interval_seconds"),
                rs.getInt("timeout_ms"),
                rs.getInt("failures_before_down"),
                RunFrom.valueOf(rs.getString("run_from")),
                rs.getString("server_id"),
                rs.getInt("enabled") != 0,
                rs.getLong("created_at"),
                CheckSource.valueOf(rs.getString("source")),
                rs.getInt("removed_from_file") != 0);
    }

    public void insert(CheckDef c) {
        jdbc.sql("INSERT INTO checks (id, name, type, target, config, interval_seconds, timeout_ms, "
                        + "failures_before_down, run_from, server_id, enabled, created_at, source, removed_from_file) "
                        + "VALUES (:id, :name, :type, :target, :config, :interval, :timeout, :failures, "
                        + ":runFrom, :serverId, :enabled, :createdAt, :source, :removed)")
                .params(bind(c))
                .update();
    }

    public void update(CheckDef c) {
        jdbc.sql("UPDATE checks SET name = :name, type = :type, target = :target, config = :config, "
                        + "interval_seconds = :interval, timeout_ms = :timeout, failures_before_down = :failures, "
                        + "run_from = :runFrom, server_id = :serverId, enabled = :enabled, source = :source, "
                        + "removed_from_file = :removed WHERE id = :id")
                .params(bind(c))
                .update();
    }

    private Map<String, Object> bind(CheckDef c) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("id", c.id());
        p.put("name", c.name());
        p.put("type", c.type().name());
        p.put("target", c.target());
        p.put("config", json.writeValueAsString(c.config()));
        p.put("interval", c.intervalSeconds());
        p.put("timeout", c.timeoutMs());
        p.put("failures", c.failuresBeforeDown());
        p.put("runFrom", c.runFrom().name());
        p.put("serverId", c.serverId());
        p.put("enabled", c.enabled() ? 1 : 0);
        p.put("createdAt", c.createdAt());
        p.put("source", c.source().name());
        p.put("removed", c.removedFromFile() ? 1 : 0);
        return p;
    }

    public Optional<CheckDef> find(String id) {
        return jdbc.sql("SELECT * FROM checks WHERE id = :id").param("id", id).query(mapper).optional();
    }

    public List<CheckDef> findAll() {
        return jdbc.sql("SELECT * FROM checks ORDER BY name").query(mapper).list();
    }

    public List<CheckDef> findHubEnabled() {
        return jdbc.sql("SELECT * FROM checks WHERE enabled = 1 AND run_from = 'HUB'").query(mapper).list();
    }

    public List<CheckDef> findByServer(String serverId) {
        return jdbc.sql("SELECT * FROM checks WHERE server_id = :s ORDER BY name")
                .param("s", serverId)
                .query(mapper)
                .list();
    }

    public List<CheckDef> findAgentEnabled(String serverId) {
        return jdbc.sql("SELECT * FROM checks WHERE server_id = :s AND run_from = 'AGENT' AND enabled = 1 ORDER BY name")
                .param("s", serverId)
                .query(mapper)
                .list();
    }

    /** A check an agent declared, with the key that tells it apart from the others on its server. */
    public record Declared(CheckDef def, String key) {
    }

    public List<Declared> findDeclared(String serverId) {
        return jdbc.sql("SELECT * FROM checks WHERE server_id = :s AND declared_key IS NOT NULL")
                .param("s", serverId)
                .query((rs, i) -> new Declared(mapper.mapRow(rs, i), rs.getString("declared_key")))
                .list();
    }

    public void setDeclaredKey(String id, String key) {
        jdbc.sql("UPDATE checks SET declared_key = :key WHERE id = :id").param("key", key).param("id", id).update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM checks WHERE id = :id").param("id", id).update() > 0;
    }
}
