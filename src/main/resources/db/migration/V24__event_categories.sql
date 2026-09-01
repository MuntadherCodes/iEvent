-- iEvent V24: an event can belong to up to 3 categories. events.category
-- stays as the PRIMARY category (drives the card label, cover theme and
-- related-events matching); this junction table holds the full ordered set.
-- Code that filters by category checks BOTH (primary OR junction row) so
-- events created without junction rows (older code paths, seeds) keep
-- matching.
CREATE TABLE event_categories (
    event_id   BIGINT      NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    category   VARCHAR(20) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, category)
);

CREATE INDEX idx_event_categories_category ON event_categories(category);

-- Backfill: every existing event's single category becomes its (only) row.
INSERT INTO event_categories (event_id, category, sort_order)
SELECT id, category, 0 FROM events;
