package dev.svcmon.hub.store;

import java.util.List;
import java.util.Optional;

import dev.svcmon.hub.model.Scope;
import dev.svcmon.hub.model.TokenRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TokenRepo {

    private static final RowMapper<TokenRecord> MAPPER = (rs, i) -> new TokenRecord(
            rs.getString("id"),
            rs.getString("name"),
            Scope.valueOf(rs.getString("scope")),
            rs.getString("server_id"),
            rs.getString("token_hash"),
            rs.getLong("created_at"),
            Rows.lng(rs, "expires_at"),
            Rows.lng(rs, "used_at"),
            rs.getString("cluster"),
            rs.getInt("use_count"));

    private final JdbcClient jdbc;

    public TokenRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TokenRecord t) {
        jdbc.sql("INSERT INTO tokens (id, name, scope, server_id, token_hash, created_at, expires_at, used_at, cluster) "
                        + "VALUES (:id, :name, :scope, :serverId, :hash, :createdAt, :expiresAt, :usedAt, :cluster)")
                .param("id", t.id())
                .param("name", t.name())
                .param("scope", t.scope().name())
                .param("serverId", t.serverId())
                .param("hash", t.tokenHash())
                .param("createdAt", t.createdAt())
                .param("expiresAt", t.expiresAt())
                .param("usedAt", t.usedAt())
                .param("cluster", t.cluster())
                .update();
    }

    public Optional<TokenRecord> findByHash(String hash) {
        return jdbc.sql("SELECT * FROM tokens WHERE token_hash = :h").param("h", hash).query(MAPPER).optional();
    }

    public List<TokenRecord> findAll() {
        return jdbc.sql("SELECT * FROM tokens ORDER BY created_at").query(MAPPER).list();
    }

    public long countByScope(Scope scope) {
        return jdbc.sql("SELECT COUNT(*) FROM tokens WHERE scope = :s")
                .param("s", scope.name())
                .query(Long.class)
                .single();
    }

    /** Marks an enrollment token used. Returns false if it was already used (one-time use). */
    public boolean markUsed(String id, long now) {
        return jdbc.sql("UPDATE tokens SET used_at = :now WHERE id = :id AND used_at IS NULL")
                .param("id", id)
                .param("now", now)
                .update() > 0;
    }

    /** Counts one more server registered with a join key and remembers when. */
    public void recordJoin(String id, long now) {
        jdbc.sql("UPDATE tokens SET use_count = use_count + 1, used_at = :now WHERE id = :id")
                .param("id", id)
                .param("now", now)
                .update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM tokens WHERE id = :id").param("id", id).update() > 0;
    }
}
