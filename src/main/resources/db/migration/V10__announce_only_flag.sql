-- iEvent V10: "announce only" (no ticketing) becomes an independent flag
-- instead of a fourth location_type value — an event can still have a real
-- venue, be online, or be TBA while also carrying no tickets. V9's location
-- type addition is left in place (harmless, just unused going forward) since
-- already-applied migrations aren't edited; existing ANNOUNCE_ONLY rows are
-- backfilled to VENUE + announce_only=true so they keep behaving the same way.

ALTER TABLE events
    ADD COLUMN announce_only BOOLEAN NOT NULL DEFAULT FALSE;

-- Clear the placeholder venue name ("Announcement only", stored by the old
-- 4th-radio flow) before backfilling to VENUE, so it doesn't linger as a fake
-- real venue name; NULL renders the same "no venue set yet" state VENUE
-- already handles.
UPDATE events
    SET announce_only = TRUE, location_type = 'VENUE', venue_name = NULL
    WHERE location_type = 'ANNOUNCE_ONLY';
