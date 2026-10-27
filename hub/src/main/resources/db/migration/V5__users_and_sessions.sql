-- People who sign in to the dashboard with a name and password. The role is ADMIN or READ; the password is kept
-- only as a PBKDF2 hash (PasswordHasher). must_change_password is set for the initial and temporary passwords.
CREATE TABLE users (
    id                   TEXT PRIMARY KEY,
    username             TEXT NOT NULL COLLATE NOCASE UNIQUE,
    role                 TEXT NOT NULL CHECK (role IN ('ADMIN', 'READ')),
    password_hash        TEXT NOT NULL,
    must_change_password INTEGER NOT NULL DEFAULT 0,
    disabled             INTEGER NOT NULL DEFAULT 0,
    created_at           INTEGER NOT NULL,
    password_changed_at  INTEGER,
    last_sign_in_at      INTEGER
);

-- A signed-in browser. Only the SHA-256 of the cookie's value is kept.
CREATE TABLE sessions (
    id             TEXT PRIMARY KEY,
    user_id        TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash     TEXT NOT NULL UNIQUE,
    created_at     INTEGER NOT NULL,
    last_seen_at   INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL,
    client_address TEXT,
    user_agent     TEXT
);

CREATE INDEX idx_sessions_user ON sessions (user_id);

-- The local admin's sign-ins were ADMIN tokens with this name; people now sign in with sessions.
DELETE FROM tokens WHERE name = 'local-login';
