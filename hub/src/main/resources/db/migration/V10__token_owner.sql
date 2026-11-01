-- The user a token acts for (TokenService): the signed-in user who made it, or the user the token it was made with acts
-- for. Such a token is suspended while that user is disabled, can only read while they are read-only, and goes with
-- them when they are deleted (the cascade does it, so no path that deletes a user can leave their tokens working).
-- Null for a token that acts for nobody: one the hub made at start-up, one made with such a token, an agent's own
-- credential, and every token made before this migration.
ALTER TABLE tokens ADD COLUMN owner_user_id TEXT REFERENCES users (id) ON DELETE CASCADE;

CREATE INDEX idx_tokens_owner ON tokens (owner_user_id);
