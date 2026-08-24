-- Super-admin moderation: per-event takedown and per-organization suspension,
-- both independent of the host's own status/visibility controls so only the
-- admin can clear them.

ALTER TABLE events ADD COLUMN admin_hidden boolean NOT NULL DEFAULT false;
ALTER TABLE events ADD COLUMN admin_hidden_at timestamptz;

ALTER TABLE organizations ADD COLUMN disabled boolean NOT NULL DEFAULT false;
ALTER TABLE organizations ADD COLUMN disabled_at timestamptz;
