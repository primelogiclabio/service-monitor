package io.primelogiclab.svcmon.hub.store;

import java.util.List;
import java.util.Optional;

import io.primelogiclab.svcmon.hub.model.Maintenance;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MaintenanceRepo {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<Maintenance> mapper;

    public MaintenanceRepo(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Maintenance(
                rs.getString("server_id"),
                rs.getLong("started_at"),
                rs.getLong("ends_at"),
                rs.getString("reason"),
                rs.getString("started_by"),
                rs.getString("started_by_kind"),
                rs.getInt("until_back") != 0,
                json.readValue(rs.getString("snapshot"), Maintenance.Snapshot.class),
                Rows.lng(rs, "disturbed_at"));
    }

    public void insert(Maintenance m) {
        jdbc.sql("INSERT INTO maintenance (server_id, started_at, ends_at, reason, started_by, started_by_kind, "
                        + "until_back, snapshot, disturbed_at) VALUES (:serverId, :startedAt, :endsAt, :reason, "
                        + ":startedBy, :startedByKind, :untilBack, :snapshot, :disturbedAt)")
                .param("serverId", m.serverId())
                .param("startedAt", m.startedAt())
                .param("endsAt", m.endsAt())
                .param("reason", m.reason())
                .param("startedBy", m.startedBy())
                .param("startedByKind", m.startedByKind())
                .param("untilBack", m.untilBack() ? 1 : 0)
                .param("snapshot", json.writeValueAsString(m.snapshot()))
                .param("disturbedAt", m.disturbedAt())
                .update();
    }

    /** A window opened again while open: a new end, reason and way of ending; what it started from is kept. */
    public void extend(String serverId, long endsAt, String reason, boolean untilBack) {
        jdbc.sql("UPDATE maintenance SET ends_at = :endsAt, reason = :reason, until_back = :untilBack "
                        + "WHERE server_id = :serverId")
                .param("serverId", serverId)
                .param("endsAt", endsAt)
                .param("reason", reason)
                .param("untilBack", untilBack ? 1 : 0)
                .update();
    }

    /** Remembers that something went down at {@code ts}; only ever moves the time forward. */
    public void markDisturbed(String serverId, long ts) {
        jdbc.sql("UPDATE maintenance SET disturbed_at = :ts "
                        + "WHERE server_id = :serverId AND (disturbed_at IS NULL OR disturbed_at < :ts)")
                .param("serverId", serverId)
                .param("ts", ts)
                .update();
    }

    public Optional<Maintenance> find(String serverId) {
        return jdbc.sql("SELECT * FROM maintenance WHERE server_id = :serverId")
                .param("serverId", serverId)
                .query(mapper)
                .optional();
    }

    public List<Maintenance> findAll() {
        return jdbc.sql("SELECT * FROM maintenance ORDER BY ends_at").query(mapper).list();
    }

    public boolean delete(String serverId) {
        return jdbc.sql("DELETE FROM maintenance WHERE server_id = :serverId").param("serverId", serverId).update() > 0;
    }
}
