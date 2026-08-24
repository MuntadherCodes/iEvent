-- iEvent V17: cash-on-arrival payment method, per-event payment-method
-- selection, and a per-event "require payment proof" toggle.

ALTER TABLE payment_methods
    ADD COLUMN method_type VARCHAR(10) NOT NULL DEFAULT 'TRANSFER'
        CHECK (method_type IN ('TRANSFER', 'CASH'));

ALTER TABLE events
    ADD COLUMN require_payment_proof BOOLEAN NOT NULL DEFAULT TRUE;

-- Empty for an event = "use every enabled org payment method" (the default,
-- dynamic link — a method added to the org later is automatically included).
-- Any rows present = "only these methods apply to this event".
CREATE TABLE event_payment_methods (
    event_id          BIGINT NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    payment_method_id BIGINT NOT NULL REFERENCES payment_methods (id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, payment_method_id)
);
CREATE INDEX ix_event_payment_methods_event ON event_payment_methods (event_id);

ALTER TABLE orders DROP CONSTRAINT orders_payment_method_check;
ALTER TABLE orders ADD CONSTRAINT orders_payment_method_check
    CHECK (payment_method IN ('FREE', 'DIRECT_TRANSFER', 'CASH'));
