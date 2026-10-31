-- People who sign in with an identity provider (svcmon.oidc). Such a user is linked by the provider's issuer and
-- subject, never by name, and has no password: password_hash holds a marker that no password matches.
ALTER TABLE users ADD COLUMN provider_issuer TEXT;
ALTER TABLE users ADD COLUMN provider_subject TEXT;

CREATE UNIQUE INDEX idx_users_provider ON users (provider_issuer, provider_subject) WHERE provider_subject IS NOT NULL;

-- How a sign-in was made: 'password' or 'provider'. From outside, a hub with a provider takes only the provider's.
ALTER TABLE sessions ADD COLUMN method TEXT NOT NULL DEFAULT 'password' CHECK (method IN ('password', 'provider'));
