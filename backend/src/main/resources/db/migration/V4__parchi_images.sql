-- V4 (Phase 6): uploaded parchi photos, linked to requests by id.
-- order_requests.image_path (V1) is left in place but no longer used; image_id replaces it.

CREATE TABLE parchi_images (
    id           UUID         PRIMARY KEY,
    customer_id  BIGINT       NOT NULL REFERENCES users (id),
    file_path    VARCHAR(255) NOT NULL,          -- relative to app.parchi.upload-dir, e.g. 2026-11/<uuid>.jpg
    content_type VARCHAR(20)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_parchi_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_parchi_size CHECK (size_bytes > 0)
);

CREATE INDEX ix_parchi_images_customer ON parchi_images (customer_id);
CREATE INDEX ix_parchi_images_created  ON parchi_images (created_at);

ALTER TABLE order_requests
    ADD COLUMN image_id UUID REFERENCES parchi_images (id);

CREATE INDEX ix_req_image ON order_requests (image_id);
