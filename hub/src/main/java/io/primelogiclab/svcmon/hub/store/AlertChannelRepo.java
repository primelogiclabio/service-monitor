package io.primelogiclab.svcmon.hub.store;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.primelogiclab.svcmon.hub.model.AlertChannel;
import io.primelogiclab.svcmon.hub.model.AlertType;
import io.primelogiclab.svcmon.hub.model.Status;
import io.primelogiclab.svcmon.hub.secrets.SecretBox;
import io.primelogiclab.svcmon.hub.secrets.SecretFields;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AlertChannelRepo {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<AlertChannel> mapper;

    private final SecretBox box;

    /** The secrets in a channel's config are stored encrypted with the hub's key; everything above sees them plain. */
    public AlertChannelRepo(JdbcClient jdbc, JsonMapper json, SecretBox box) {
        this.jdbc = jdbc;
        this.json = json;
        this.box = box;
        this.mapper = (rs, i) -> {
            AlertType type = AlertType.valueOf(rs.getString("type"));
            return new AlertChannel(
                    rs.getString("id"),
                    rs.getString("name"),
                    type,
                    SecretFields.channel(type, json.readValue(rs.getString("config"), MAP_TYPE), box::open),
                    parseStatuses(rs.getString("statuses")),
                    rs.getInt("enabled") != 0,
                    rs.getLong("created_at"),
                    Rows.lng(rs, "last_attempt_at"),
                    Rows.lng(rs, "last_success_at"),
                    rs.getString("last_error"));
        };
    }

    static Set<Status> parseStatuses(String csv) {
        EnumSet<Status> set = EnumSet.noneOf(Status.class);
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                set.add(Status.valueOf(part.trim()));
            }
        }
        return set;
    }

    private static String joinStatuses(Set<Status> statuses) {
        return statuses.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private Map<String, Object> bind(AlertChannel c) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", c.id());
        p.put("name", c.name());
        p.put("type", c.type().name());
        p.put("config", json.writeValueAsString(SecretFields.channel(c.type(), c.config(), box::seal)));
        p.put("statuses", joinStatuses(c.statuses()));
        p.put("enabled", c.enabled() ? 1 : 0);
        p.put("createdAt", c.createdAt());
        return p;
    }

    public void insert(AlertChannel c) {
        jdbc.sql("INSERT INTO alert_channels (id, name, type, config, statuses, enabled, created_at) "
                        + "VALUES (:id, :name, :type, :config, :statuses, :enabled, :createdAt)")
                .params(bind(c))
                .update();
    }

    public void update(AlertChannel c) {
        jdbc.sql("UPDATE alert_channels SET name = :name, type = :type, config = :config, "
                        + "statuses = :statuses, enabled = :enabled WHERE id = :id")
                .params(bind(c))
                .update();
    }

    public Optional<AlertChannel> find(String id) {
        return jdbc.sql("SELECT * FROM alert_channels WHERE id = :id").param("id", id).query(mapper).optional();
    }

    public Optional<AlertChannel> findByName(String name) {
        return jdbc.sql("SELECT * FROM alert_channels WHERE name = :name").param("name", name).query(mapper).optional();
    }

    public List<AlertChannel> findAll() {
        return jdbc.sql("SELECT * FROM alert_channels ORDER BY name").query(mapper).list();
    }

    public List<AlertChannel> findEnabled() {
        return jdbc.sql("SELECT * FROM alert_channels WHERE enabled = 1").query(mapper).list();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM alert_channels WHERE id = :id").param("id", id).update() > 0;
    }

    /** Remembers the outcome of the latest delivery so the API can show whether a channel works. */
    public void recordAttempt(String id, long at, boolean success, String error) {
        jdbc.sql("UPDATE alert_channels SET last_attempt_at = :at, "
                        + "last_success_at = CASE WHEN :ok = 1 THEN :at ELSE last_success_at END, "
                        + "last_error = :error WHERE id = :id")
                .param("id", id)
                .param("at", at)
                .param("ok", success ? 1 : 0)
                .param("error", success ? null : error)
                .update();
    }
}
