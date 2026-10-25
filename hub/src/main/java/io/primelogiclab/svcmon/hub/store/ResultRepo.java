package io.primelogiclab.svcmon.hub.store;

import java.util.List;

import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.Status;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ResultRepo {

    private static final RowMapper<CheckResult> MAPPER = (rs, i) -> new CheckResult(
            rs.getString("check_id"),
            rs.getLong("ts"),
            Status.valueOf(rs.getString("status")),
            Rows.dbl(rs, "latency_ms"),
            Rows.dbl(rs, "value"),
            rs.getString("message"));

    private final JdbcClient jdbc;

    public ResultRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CheckResult r) {
        jdbc.sql("INSERT INTO results (check_id, ts, status, latency_ms, value, message) "
                        + "VALUES (:id, :ts, :status, :latency, :value, :message)")
                .param("id", r.checkId())
                .param("ts", r.ts())
                .param("status", r.status().name())
                .param("latency", r.latencyMs())
                .param("value", r.value())
                .param("message", r.message())
                .update();
    }

    /** Newest first, at most {@code limit} rows with ts >= since. */
    public List<CheckResult> recent(String checkId, long since, int limit) {
        return jdbc.sql("SELECT * FROM results WHERE check_id = :id AND ts >= :since ORDER BY ts DESC LIMIT :limit")
                .param("id", checkId)
                .param("since", since)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    public int deleteOlderThan(long ts) {
        return jdbc.sql("DELETE FROM results WHERE ts < :ts").param("ts", ts).update();
    }
}
