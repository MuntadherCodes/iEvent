-- iEvent V4: uploaded event cover images (theme gradients remain the fallback)
ALTER TABLE events
    ADD COLUMN cover_image_path VARCHAR(255);
