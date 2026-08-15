-- iEvent V2: orders, order items, tickets (QR), organizer direct-payment profile

-- Direct-payment (QR / card transfer) details live on the organization.
ALTER TABLE organizations
    ADD COLUMN direct_payments_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN pay_card_number         VARCHAR(32),
    ADD COLUMN pay_account_name        VARCHAR(120),
    ADD COLUMN pay_wallet_bank         VARCHAR(60),
    ADD COLUMN pay_instructions        TEXT;

CREATE TABLE orders (
    id                BIGSERIAL PRIMARY KEY,
    order_code        VARCHAR(20)  NOT NULL,
    event_id          BIGINT       NOT NULL REFERENCES events (id),
    buyer_user_id     BIGINT       NOT NULL REFERENCES users (id),
    buyer_name        VARCHAR(120) NOT NULL,
    buyer_email       VARCHAR(255) NOT NULL,
    buyer_phone       VARCHAR(32),
    payment_method    VARCHAR(20)  NOT NULL
                      CHECK (payment_method IN ('FREE', 'DIRECT_TRANSFER')),
    status            VARCHAR(24)  NOT NULL
                      CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    subtotal_iqd      BIGINT       NOT NULL DEFAULT 0,
    booking_fee_iqd   BIGINT       NOT NULL DEFAULT 0,
    total_iqd         BIGINT       NOT NULL DEFAULT 0,
    transfer_reference VARCHAR(80),
    receipt_path      VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_orders_code ON orders (order_code);
CREATE INDEX ix_orders_event_status ON orders (event_id, status);
CREATE INDEX ix_orders_buyer ON orders (buyer_user_id, created_at DESC);

CREATE TABLE order_items (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    ticket_type_id  BIGINT NOT NULL REFERENCES ticket_types (id),
    quantity        INT    NOT NULL CHECK (quantity > 0),
    unit_price_iqd  BIGINT NOT NULL CHECK (unit_price_iqd >= 0)
);
CREATE INDEX ix_order_items_order ON order_items (order_id);

CREATE TABLE tickets (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL,
    order_id        BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    ticket_type_id  BIGINT       NOT NULL REFERENCES ticket_types (id),
    event_id        BIGINT       NOT NULL REFERENCES events (id),
    holder_name     VARCHAR(120) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'VALID'
                    CHECK (status IN ('VALID', 'CHECKED_IN', 'VOID')),
    checked_in_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tickets_code ON tickets (code);
CREATE INDEX ix_tickets_event_status ON tickets (event_id, status);
CREATE INDEX ix_tickets_order ON tickets (order_id);
