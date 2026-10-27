package io.primelogiclab.svcmon.hub.store;

import java.util.List;
import java.util.Optional;

import io.primelogiclab.svcmon.hub.model.SessionRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepo {

    private static final RowMapper<SessionRecord> MAPPER = (rs, i) -> new SessionRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("token_hash"),
            rs.getLong("created_at"),
            rs.getLong("last_seen_at"),
            rs.getLong("expires_at"),
            rs.getString("client_address"),
            rs.getString("user_agent"));

    private final JdbcClient jdbc;

    public SessionRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SessionRecord s) {
        jdbc.sql("INSERT INTO sessions (id, user_id, token_hash, created_at, last_seen_at, expires_at, client_address, "
                        + "user_agent) VALUES (:id, :userId, :hash, :createdAt, :lastSeenAt, :expiresAt, :address, :agent)")
                .param("id", s.id())
                .param("userId", s.userId())
                .param("hash", s.tokenHash())
                .param("createdAt", s.createdAt())
                .param("lastSeenAt", s.lastSeenAt())
                .param("expiresAt", s.expiresAt())
                .param("address", s.clientAddress())
                .param("agent", s.userAgent())
                .update();
    }

    public Optional<SessionRecord> findByHash(String hash) {
        return jdbc.sql("SELECT * FROM sessions WHERE token_hash = :h").param("h", hash).query(MAPPER).optional();
    }

    public Optional<SessionRecord> findById(String id) {
        return jdbc.sql("SELECT * FROM sessions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<SessionRecord> findByUser(String userId) {
        return jdbc.sql("SELECT * FROM sessions WHERE user_id = :u ORDER BY created_at").param("u", userId)
                .query(MAPPER).list();
    }

    public List<SessionRecord> findAll() {
        return jdbc.sql("SELECT * FROM sessions ORDER BY created_at").query(MAPPER).list();
    }

    public void touch(String id, long now) {
        jdbc.sql("UPDATE sessions SET last_seen_at = :now WHERE id = :id").param("id", id).param("now", now).update();
    }

    /** Gives the session a new cookie value, so one that was seen before (for example on a shared screen) stops working. */
    public void rehash(String id, String hash) {
        jdbc.sql("UPDATE sessions SET token_hash = :h WHERE id = :id").param("id", id).param("h", hash).update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM sessions WHERE id = :id").param("id", id).update() > 0;
    }

    public int deleteByUser(String userId) {
        return jdbc.sql("DELETE FROM sessions WHERE user_id = :u").param("u", userId).update();
    }

    public int deleteByUserExcept(String userId, String keepId) {
        return jdbc.sql("DELETE FROM sessions WHERE user_id = :u AND id <> :keep")
                .param("u", userId)
                .param("keep", keepId)
                .update();
    }

    /** Removes sessions past their end, or not used since {@code idleCutoff}. */
    public int deleteEnded(long now, long idleCutoff) {
        return jdbc.sql("DELETE FROM sessions WHERE expires_at <= :now OR last_seen_at < :idle")
                .param("now", now)
                .param("idle", idleCutoff)
                .update();
    }
}
