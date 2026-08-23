-- Pending team invites: lets an owner invite someone by email who doesn't
-- have an iEvent account yet. A real invite email carries the token; the
-- row is deleted (cancel) or marked accepted (accept) rather than reused.
CREATE TABLE team_invites (
    id                 BIGSERIAL PRIMARY KEY,
    organization_id    BIGINT       NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email              VARCHAR(255) NOT NULL,
    role               VARCHAR(16)  NOT NULL CHECK (role IN ('MANAGER', 'STAFF')),
    token              VARCHAR(64)  NOT NULL,
    invited_by_user_id BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    accepted_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_team_invite_token ON team_invites (token);
-- Only one live (unaccepted) invite per org+email at a time — re-inviting
-- refreshes this same row (new token/expiry) instead of creating a duplicate.
CREATE UNIQUE INDEX ux_team_invite_org_email_pending ON team_invites (organization_id, lower(email)) WHERE accepted_at IS NULL;
