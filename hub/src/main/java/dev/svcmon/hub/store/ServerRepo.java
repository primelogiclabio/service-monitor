package dev.svcmon.hub.store;

import java.util.List;
import java.util.Optional;

import dev.svcmon.hub.model.ServerInfo;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ServerRepo {

    private static final RowMapper<ServerInfo> MAPPER = (rs, i) -> new ServerInfo(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("cluster"),
            rs.getString("os"),
            rs.getString("agent_version"),
            Rows.lng(rs, "last_seen"),
            rs.getLong("created_at"));

    private final JdbcClient jdbc;

    public ServerRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ServerInfo s) {
        jdbc.sql("INSERT INTO servers (id, name, cluster, os, agent_version, last_seen, created_at) "
                        + "VALUES (:id, :name, :cluster, :os, :version, :lastSeen, :createdAt)")
                .param("id", s.id())
                .param("name", s.name())
                .param("cluster", s.cluster())
                .param("os", s.os())
                .param("version", s.agentVersion())
                .param("lastSeen", s.lastSeen())
                .param("createdAt", s.createdAt())
                .update();
    }

    public Optional<ServerInfo> find(String id) {
        return jdbc.sql("SELECT * FROM servers WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<ServerInfo> findByName(String name) {
        return jdbc.sql("SELECT * FROM servers WHERE name = :name").param("name", name).query(MAPPER).optional();
    }

    public List<ServerInfo> findAll() {
        return jdbc.sql("SELECT * FROM servers ORDER BY name").query(MAPPER).list();
    }

    public void update(String id, String name, String cluster) {
        jdbc.sql("UPDATE servers SET name = :name, cluster = :cluster WHERE id = :id")
                .param("id", id)
                .param("name", name)
                .param("cluster", cluster)
                .update();
    }

    /** Records a heartbeat; os and version are only overwritten when supplied. */
    public void touch(String id, long now, String os, String agentVersion) {
        jdbc.sql("UPDATE servers SET last_seen = :now, os = COALESCE(:os, os), "
                        + "agent_version = COALESCE(:version, agent_version) WHERE id = :id")
                .param("id", id)
                .param("now", now)
                .param("os", os)
                .param("version", agentVersion)
                .update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM servers WHERE id = :id").param("id", id).update() > 0;
    }
}
