package io.primelogiclab.svcmon.hub.store;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MetaRepo {

    private final JdbcClient jdbc;

    public MetaRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM hub_meta WHERE key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
    }

    public void set(String key, String value) {
        jdbc.sql("INSERT INTO hub_meta (key, value) VALUES (:key, :value) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .param("key", key)
                .param("value", value)
                .update();
    }

    public void delete(String key) {
        jdbc.sql("DELETE FROM hub_meta WHERE key = :key").param("key", key).update();
    }
}
