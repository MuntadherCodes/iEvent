-- iEvent V25: Round 27 settings
--  * organizations.refund_policy / refund_policy_visible: the refund policy
--    becomes an organizer-level setting (with a show/hide switch for event
--    pages) instead of a per-event wizard field. Existing events keep their
--    own refund_policy column, but display now reads the organizer's.
--  * events.auto_confirm_orders: per-event opt-out of manual verification
--    (paid orders confirmed + ticketed immediately). Off by default.
--  * direct payments default ON for organizers created from now on (the Java
--    default changed); existing rows are left as they are.
ALTER TABLE organizations
    ADD COLUMN refund_policy VARCHAR(24) NOT NULL DEFAULT 'NO_REFUNDS'
        CHECK (refund_policy IN ('NO_REFUNDS', 'UP_TO_48H', 'UP_TO_7_DAYS')),
    ADD COLUMN refund_policy_visible BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE events
    ADD COLUMN auto_confirm_orders BOOLEAN NOT NULL DEFAULT FALSE;
