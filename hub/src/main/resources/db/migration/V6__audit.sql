-- Who signed in, and who changed what (AuditService). category is sign_in or change; kind says what happened and
-- outcome how it ended (ok, or the refusal's code). actor is a user name, a token's name or 'unknown', never a
-- password or token. No foreign key to users: an event outlives the user it names. Request bodies are never kept.
CREATE TABLE audit_events (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    at         INTEGER NOT NULL,
    category   TEXT NOT NULL CHECK (category IN ('sign_in', 'change')),
    kind       TEXT NOT NULL,
    outcome    TEXT NOT NULL,
    actor      TEXT,
    actor_kind TEXT,
    address    TEXT,
    network    TEXT,
    action     TEXT,
    target     TEXT,
    detail     TEXT
);

CREATE INDEX idx_audit_category_at ON audit_events (category, at);
