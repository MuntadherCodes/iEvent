-- iEvent V15: per-image vertical crop focus, 0 (top) to 100 (bottom), 50 =
-- centered. Previously only events.cover_focus_y existed, positioning just
-- the primary cover — extra gallery images (the auto-slider) had no way to
-- be repositioned individually. INTEGER to match the Java `int` field
-- (V13/V14 already hit the SMALLINT-vs-INTEGER Hibernate validation trap).
ALTER TABLE event_images
    ADD COLUMN focus_y INTEGER NOT NULL DEFAULT 50;
