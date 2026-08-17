-- iEvent V5: wireframe-parity round — content depth, engagement, refunds,
-- tracking links, password resets, campaigns, org branding/contacts, user prefs.

ALTER TABLE events
    ADD COLUMN view_count    BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN summary       VARCHAR(160),
    ADD COLUMN tags          VARCHAR(255),                 -- comma-separated
    ADD COLUMN lineup        TEXT,                          -- one act per line: "Name — 10:00 PM"
    ADD COLUMN visibility    VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC'
        CHECK (visibility IN ('PUBLIC', 'UNLISTED')),
    ADD COLUMN refund_policy VARCHAR(24)  NOT NULL DEFAULT 'UP_TO_7_DAYS'
        CHECK (refund_policy IN ('NO_REFUNDS', 'UP_TO_48H', 'UP_TO_7_DAYS'));

-- Refunded orders (money returned offline for direct transfers; tickets voided)
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'REFUNDED'));

ALTER TABLE tickets
    ADD COLUMN holder_email VARCHAR(255);

ALTER TABLE organizations
    ADD COLUMN contact_email VARCHAR(255),
    ADD COLUMN contact_phone VARCHAR(32),
    ADD COLUMN website       VARCHAR(255),
    ADD COLUMN instagram     VARCHAR(80),
    ADD COLUMN logo_path     VARCHAR(255),
    ADD COLUMN brand_color   VARCHAR(9),
    ADD COLUMN notify_pending_orders BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users
    ADD COLUMN city      VARCHAR(60),
    ADD COLUMN interests VARCHAR(400);                      -- comma-separated category keys

CREATE TABLE password_reset_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_reset_token ON password_reset_tokens (token);

CREATE TABLE tracking_links (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT      NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_id        BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    channel         VARCHAR(40) NOT NULL,
    code            VARCHAR(16) NOT NULL,
    clicks          BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tracking_code ON tracking_links (code);

CREATE TABLE campaigns (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_id        BIGINT       REFERENCES events (id) ON DELETE SET NULL,
    audience        VARCHAR(24)  NOT NULL,   -- EVENT_ATTENDEES | PAST_ATTENDEES | FOLLOWERS
    subject         VARCHAR(200) NOT NULL,
    recipients      INT          NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
