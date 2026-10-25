-- Join keys: reusable tokens that register any number of agents. A join key may pin the cluster its servers
-- land in, and counts how many servers it has registered (used_at holds the last time).
ALTER TABLE tokens ADD COLUMN cluster TEXT;
ALTER TABLE tokens ADD COLUMN use_count INTEGER NOT NULL DEFAULT 0;
