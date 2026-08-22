-- iEvent V14: V13 created cover_focus_y as SMALLINT, but a Java `int` field
-- maps to INTEGER — Hibernate's schema validator rejected the mismatch on
-- startup. Widen to match organizations.cover_focus_y, which is INTEGER.
ALTER TABLE events
    ALTER COLUMN cover_focus_y TYPE INTEGER;
