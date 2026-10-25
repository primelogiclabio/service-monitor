package io.primelogiclab.svcmon.hub.store;

import java.util.List;
import java.util.Optional;

import io.primelogiclab.svcmon.hub.model.CheckState;
import io.primelogiclab.svcmon.hub.model.Status;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StateRepo {

    private static final RowMapper<CheckState> MAPPER = (rs, i) -> new CheckState(
            rs.getString("check_id"),
            Status.valueOf(rs.getString("status")),
            rs.getInt("consecutive_failures"),
            rs.getLong("last_change"),
            Rows.lng(rs, "last_result_at"),
            Rows.dbl(rs, "last_latency_ms"),
            Rows.dbl(rs, "last_value"),
            rs.getString("last_message"));

    private final JdbcClient jdbc;

    public StateRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CheckState> find(String checkId) {
        return jdbc.sql("SELECT * FROM check_state WHERE check_id = :id").param("id", checkId).query(MAPPER).optional();
    }

    public List<CheckState> findAll() {
        return jdbc.sql("SELECT * FROM check_state").query(MAPPER).list();
    }

    public void upsert(CheckState s) {
        jdbc.sql("INSERT INTO check_state (check_id, status, consecutive_failures, last_change, last_result_at, "
                        + "last_latency_ms, last_value, last_message) "
                        + "VALUES (:id, :status, :fails, :change, :at, :latency, :value, :message) "
                        + "ON CONFLICT(check_id) DO UPDATE SET status = excluded.status, "
                        + "consecutive_failures = excluded.consecutive_failures, last_change = excluded.last_change, "
                        + "last_result_at = excluded.last_result_at, last_latency_ms = excluded.last_latency_ms, "
                        + "last_value = excluded.last_value, last_message = excluded.last_message")
                .param("id", s.checkId())
                .param("status", s.status().name())
                .param("fails", s.consecutiveFailures())
                .param("change", s.lastChange())
                .param("at", s.lastResultAt())
                .param("latency", s.lastLatencyMs())
                .param("value", s.lastValue())
                .param("message", s.lastMessage())
                .update();
    }
}
