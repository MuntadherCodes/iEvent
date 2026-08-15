-- iEvent V1: users, organizations, events, ticket types, likes, follows
-- All money amounts are whole Iraqi dinars (IQD) stored as BIGINT.

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    phone           VARCHAR(32),
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER'
                    CHECK (role IN ('USER', 'HOST', 'ADMIN')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE organizations (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL REFERENCES users (id),
    name            VARCHAR(120) NOT NULL,
    handle          VARCHAR(60)  NOT NULL,
    bio             TEXT,
    city            VARCHAR(60),
    verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_organizations_handle ON organizations (lower(handle));

CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organizations (id),
    title           VARCHAR(160) NOT NULL,
    slug            VARCHAR(180) NOT NULL,
    category        VARCHAR(32)  NOT NULL
                    CHECK (category IN ('MUSIC','TECH','BUSINESS','ARTS','FOOD','SPORTS',
                                        'COMMUNITY','EDUCATION','FILM','FAMILY')),
    description     TEXT         NOT NULL DEFAULT '',
    city            VARCHAR(60)  NOT NULL,
    venue_name      VARCHAR(160),
    venue_address   VARCHAR(255),
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','LIVE','ENDED','CANCELLED')),
    cover_theme     VARCHAR(24)  NOT NULL DEFAULT 'community',
    language        VARCHAR(8)   NOT NULL DEFAULT 'en',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_events_slug ON events (slug);
CREATE INDEX ix_events_status_starts ON events (status, starts_at);
CREATE INDEX ix_events_city ON events (city);
CREATE INDEX ix_events_category ON events (category);

CREATE TABLE ticket_types (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT       NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name            VARCHAR(80)  NOT NULL,
    price_iqd       BIGINT       NOT NULL DEFAULT 0 CHECK (price_iqd >= 0),
    quantity        INT          NOT NULL CHECK (quantity >= 0),
    sold            INT          NOT NULL DEFAULT 0 CHECK (sold >= 0),
    sort_order      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ON_SALE'
                    CHECK (status IN ('ON_SALE','SOLD_OUT','HIDDEN','ENDED'))
);
CREATE INDEX ix_ticket_types_event ON ticket_types (event_id);

CREATE TABLE event_likes (
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_id        BIGINT NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_id)
);
CREATE INDEX ix_event_likes_event ON event_likes (event_id);

CREATE TABLE follows (
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, organization_id)
);
