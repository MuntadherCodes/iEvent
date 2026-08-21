-- iEvent V9: add ANNOUNCE_ONLY as a fourth event location type — a purely
-- informational listing with no venue/online link and no ticketing.
-- Widen the column ("ANNOUNCE_ONLY" is 13 chars, past the old varchar(12))
-- and extend the check constraint to allow the new value.

ALTER TABLE events
    ALTER COLUMN location_type TYPE VARCHAR(20);

ALTER TABLE events
    DROP CONSTRAINT events_location_type_check;

ALTER TABLE events
    ADD CONSTRAINT events_location_type_check
    CHECK (location_type IN ('VENUE', 'ONLINE', 'TBA', 'ANNOUNCE_ONLY'));
