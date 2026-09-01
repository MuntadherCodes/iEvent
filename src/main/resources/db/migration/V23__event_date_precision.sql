-- iEvent V23: flexible event dates. Hosts can now schedule an event as an
-- exact day (DAY, the default and the only behavior until now), a multi-day
-- range (RANGE — ends_at moves to a later calendar day), month-and-year only
-- (MONTH — starts_at stores the first of that month at noon Baghdad as a
-- placeholder), or date-to-be-announced (TBA — starts_at stores the far-future
-- placeholder 2099-12-31 so the NOT NULL column and every existing sort keep
-- working; display code renders "Date TBA" instead of the placeholder).
ALTER TABLE events
    ADD COLUMN date_precision VARCHAR(10) NOT NULL DEFAULT 'DAY';

ALTER TABLE events
    ADD CONSTRAINT events_date_precision_check
    CHECK (date_precision IN ('DAY', 'RANGE', 'MONTH', 'TBA'));

-- Existing rows that already span well past one day were de-facto multi-day;
-- classify them as RANGE so the edit form reopens in the right mode. The
-- 20-hour threshold deliberately leaves alone the old form's "ends after
-- midnight" case (a 7 PM concert ending 2 AM is still a one-day event).
UPDATE events SET date_precision = 'RANGE'
 WHERE ends_at IS NOT NULL AND ends_at - starts_at > interval '20 hours';
