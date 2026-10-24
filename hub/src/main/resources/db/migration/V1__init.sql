CREATE TABLE hub_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE servers (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE,
    cluster       TEXT,
    os            TEXT,
    agent_version TEXT,
    last_seen     INTEGER,
    created_at    INTEGER NOT NULL
);

CREATE TABLE tokens (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    scope      TEXT NOT NULL,
    server_id  TEXT REFERENCES servers (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER,
    used_at    INTEGER
);

CREATE TABLE checks (
    id                   TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    type                 TEXT NOT NULL,
    target               TEXT,
    config               TEXT NOT NULL DEFAULT '{}',
    interval_seconds     INTEGER NOT NULL DEFAULT 60,
    timeout_ms           INTEGER NOT NULL DEFAULT 5000,
    failures_before_down INTEGER NOT NULL DEFAULT 1,
    run_from             TEXT NOT NULL DEFAULT 'HUB',
    server_id            TEXT REFERENCES servers (id) ON DELETE CASCADE,
    enabled              INTEGER NOT NULL DEFAULT 1,
    created_at           INTEGER NOT NULL
);

CREATE TABLE check_state (
    check_id             TEXT PRIMARY KEY REFERENCES checks (id) ON DELETE CASCADE,
    status               TEXT NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_change          INTEGER NOT NULL,
    last_result_at       INTEGER,
    last_latency_ms      REAL,
    last_value           REAL,
    last_message         TEXT
);

CREATE TABLE results (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    check_id   TEXT NOT NULL REFERENCES checks (id) ON DELETE CASCADE,
    ts         INTEGER NOT NULL,
    status     TEXT NOT NULL,
    latency_ms REAL,
    value      REAL,
    message    TEXT
);

CREATE INDEX idx_results_check_ts ON results (check_id, ts DESC);
CREATE INDEX idx_checks_server ON checks (server_id);
