-- Who made each token (TokenService): created_by is a user name, an API token's name, the name of the join key or
-- enrollment token that registered an agent, or 'hub' for one made at start-up; created_by_kind says which (user,
-- token, join_key, enrollment_token, hub), as the audit's actor_kind does. Names, not ids and no foreign key, so the
-- record outlives the user. Tokens made before this migration have neither.
ALTER TABLE tokens ADD COLUMN created_by TEXT;
ALTER TABLE tokens ADD COLUMN created_by_kind TEXT;
