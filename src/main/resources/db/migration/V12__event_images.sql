-- Multi-image galleries for events. The existing cover_image_path (uploaded
-- file, served via /media/event-cover/{id}) stays the primary/thumbnail image
-- used everywhere a single cover is shown (cards, dashboard, checkout).
-- cover_image_url is the external-URL equivalent for a Pexels-sourced primary
-- image, used only when no file was uploaded. event_images holds any
-- additional images (Pexels or otherwise) that make the event page a slider.
ALTER TABLE events
    ADD COLUMN cover_image_url VARCHAR(500),
    ADD COLUMN cover_image_credit_name VARCHAR(160),
    ADD COLUMN cover_image_credit_url VARCHAR(500);

CREATE TABLE event_images (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    credit_name VARCHAR(160),
    credit_url  VARCHAR(500),
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_images_event ON event_images (event_id, sort_order);
