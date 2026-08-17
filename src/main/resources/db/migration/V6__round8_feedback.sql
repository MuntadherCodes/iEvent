-- iEvent V6: Round 8 user feedback — event location types (venue/online/TBA),
-- Google Maps pin, multiple direct-payment methods with QR images,
-- organizer cover image, in-app notification center.

ALTER TABLE events
    ADD COLUMN location_type VARCHAR(12) NOT NULL DEFAULT 'VENUE'
        CHECK (location_type IN ('VENUE', 'ONLINE', 'TBA')),
    ADD COLUMN online_url VARCHAR(500),   -- meeting link, shown to confirmed ticket holders only
    ADD COLUMN maps_url   VARCHAR(500);   -- optional exact-pin Google Maps link

ALTER TABLE organizations
    ADD COLUMN cover_image_path VARCHAR(255);

CREATE TABLE payment_methods (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    label           VARCHAR(60)  NOT NULL,    -- "ZainCash", "Qi Card", "Rafidain Bank" …
    account_number  VARCHAR(60),
    account_name    VARCHAR(120),
    instructions    TEXT,
    qr_image_path   VARCHAR(255),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_methods_org ON payment_methods (organization_id, sort_order);

-- Migrate the legacy single direct-pay method into the new table (idempotent by nature:
-- V6 runs once; legacy columns stay in place but the app now reads payment_methods).
INSERT INTO payment_methods (organization_id, label, account_number, account_name, instructions, enabled, sort_order)
SELECT id,
       COALESCE(NULLIF(pay_wallet_bank, ''), 'Card transfer'),
       pay_card_number, pay_account_name, pay_instructions,
       TRUE, 0
FROM organizations
WHERE pay_card_number IS NOT NULL OR pay_account_name IS NOT NULL;

CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(32)  NOT NULL,   -- ORDER_PENDING | ORDER_CONFIRMED | ORDER_REJECTED | ORDER_REFUNDED | EVENT_CANCELLED | EVENT_POSTPONED | ...
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(400),
    url        VARCHAR(300),
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
