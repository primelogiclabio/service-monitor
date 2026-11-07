-- A server's maintenance window (alerts/MaintenanceService): while it is open the hub sends no alert about the server's
-- agent or its checks. One per server; the row goes when the window ends, and the audit keeps who opened and ended it.
CREATE TABLE maintenance (
    server_id       TEXT PRIMARY KEY REFERENCES servers (id) ON DELETE CASCADE,
    started_at      INTEGER NOT NULL,
    ends_at         INTEGER NOT NULL,
    reason          TEXT,
    started_by      TEXT NOT NULL,
    started_by_kind TEXT NOT NULL,
    -- 1: end as soon as everything that went down is back; 0: stay open until ends_at or until ended by hand.
    until_back      INTEGER NOT NULL DEFAULT 1,
    -- How things stood when it opened ({"agent":"UP","checks":{"<check id>":"DOWN"}}); compared with how they stand
    -- when it ends, so whatever is not back then alerts once.
    snapshot        TEXT NOT NULL,
    -- When something last went down (or the agent was last seen away) while it was open; null while nothing has.
    disturbed_at    INTEGER
);
