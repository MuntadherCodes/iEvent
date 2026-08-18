-- iEvent V7: Round 10 feedback — booking-fee mode (pass to buyer vs absorb),
-- dismissible dashboard checklist, adjustable organizer cover position.

ALTER TABLE events
    ADD COLUMN fee_mode VARCHAR(8) NOT NULL DEFAULT 'PASS'
        CHECK (fee_mode IN ('PASS', 'ABSORB'));

ALTER TABLE organizations
    ADD COLUMN checklist_dismissed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cover_focus_y      INT     NOT NULL DEFAULT 50;   -- 0=top, 50=center, 100=bottom
