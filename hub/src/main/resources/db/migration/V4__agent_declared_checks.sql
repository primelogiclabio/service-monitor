-- A check is made in the hub (HUB) or declared by an agent from a file beside its config (AGENT).
ALTER TABLE checks ADD COLUMN source TEXT NOT NULL DEFAULT 'HUB';
-- How a declared check is told apart on its server: an id the file gives it, or its type and target. Null for hub checks.
ALTER TABLE checks ADD COLUMN declared_key TEXT;
-- Set when a declared check disappeared from the agent's file: it is switched off and kept with its history.
ALTER TABLE checks ADD COLUMN removed_from_file INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX idx_checks_declared ON checks (server_id, declared_key) WHERE declared_key IS NOT NULL;
