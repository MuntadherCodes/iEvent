-- iEvent V3: promo codes, order discounts, org team members, newsletter, user prefs

CREATE TABLE promo_codes (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT      NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_id        BIGINT      REFERENCES events (id) ON DELETE CASCADE, -- NULL = all events
    code            VARCHAR(40) NOT NULL,
    kind            VARCHAR(10) NOT NULL CHECK (kind IN ('PERCENT', 'FIXED')),
    value           BIGINT      NOT NULL CHECK (value > 0),  -- percent (1-100) or IQD amount
    max_uses        INT         NOT NULL DEFAULT 0,          -- 0 = unlimited
    used            INT         NOT NULL DEFAULT 0,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_promo_org_code ON promo_codes (organization_id, upper(code));

ALTER TABLE orders
    ADD COLUMN promo_code   VARCHAR(40),
    ADD COLUMN discount_iqd BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN holder_names TEXT;  -- newline-separated ticket holder names, in item order

CREATE TABLE org_members (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT      NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL CHECK (role IN ('MANAGER', 'STAFF')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);
CREATE INDEX ix_org_members_user ON org_members (user_id);

CREATE TABLE newsletter_subscribers (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    city       VARCHAR(60),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_newsletter_email ON newsletter_subscribers (lower(email));

ALTER TABLE users
    ADD COLUMN notify_events    BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_marketing BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN auth_provider    VARCHAR(16) NOT NULL DEFAULT 'local';
