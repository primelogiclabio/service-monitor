package io.primelogiclab.svcmon.hub.store;

import java.util.List;

import io.primelogiclab.svcmon.hub.model.AuditEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepo {

    private static final RowMapper<AuditEvent> MAPPER = (rs, i) -> new AuditEvent(
            rs.getLong("id"),
            rs.getLong("at"),
            rs.getString("category"),
            rs.getString("kind"),
            rs.getString("outcome"),
            rs.getString("actor"),
            rs.getString("actor_kind"),
            rs.getString("address"),
            rs.getString("network"),
            rs.getString("action"),
            rs.getString("target"),
            rs.getString("detail"));

    private final JdbcClient jdbc;

    public AuditRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEvent e) {
        jdbc.sql("INSERT INTO audit_events (at, category, kind, outcome, actor, actor_kind, address, network, action,"
                        + " target, detail) VALUES (:at, :category, :kind, :outcome, :actor, :actorKind, :address,"
                        + " :network, :action, :target, :detail)")
                .param("at", e.at())
                .param("category", e.category())
                .param("kind", e.kind())
                .param("outcome", e.outcome())
                .param("actor", e.actor())
                .param("actorKind", e.actorKind())
                .param("address", e.address())
                .param("network", e.network())
                .param("action", e.action())
                .param("target", e.target())
                .param("detail", e.detail())
                .update();
    }

    /** Newest first; {@code category} null means both, {@code beforeId} null means from the newest. */
    public List<AuditEvent> find(String category, Long beforeId, int limit) {
        return jdbc.sql("SELECT * FROM audit_events WHERE (:category IS NULL OR category = :category)"
                        + " AND (:before IS NULL OR id < :before) ORDER BY id DESC LIMIT :limit")
                .param("category", category)
                .param("before", beforeId)
                .param("limit", limit)
                .query(MAPPER).list();
    }

    public int deleteOlderThan(String category, long cutoff) {
        return jdbc.sql("DELETE FROM audit_events WHERE category = :category AND at < :cutoff")
                .param("category", category)
                .param("cutoff", cutoff)
                .update();
    }
}
