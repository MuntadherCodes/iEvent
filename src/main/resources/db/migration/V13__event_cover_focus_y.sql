-- iEvent V13: vertical crop focus for the event cover, mirroring
-- organizations.cover_focus_y — 0 (top) to 100 (bottom), 50 = centered.
ALTER TABLE events
    ADD COLUMN cover_focus_y SMALLINT NOT NULL DEFAULT 50;
