-- V5 (Phase 7): parchi photos are never stored any more; pickup-code attempt limit; indexes from EXPLAIN.

-- ---------------------------------------------------------------- privacy: drop photo storage (V4)
ALTER TABLE order_requests DROP COLUMN image_id;
ALTER TABLE order_requests DROP COLUMN image_path;
DROP TABLE parchi_images;

-- ---------------------------------------------------------------- pickup-code lock
ALTER TABLE order_requests
    ADD COLUMN pickup_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN pickup_locked_until    TIMESTAMPTZ,
    ADD CONSTRAINT ck_req_pickup_attempts CHECK (pickup_failed_attempts >= 0);

-- ---------------------------------------------------------------- indexes
-- Nearby / nearest-open: the lat/lng box pre-filter in StoreRepository.findNearestOpen uses this
-- instead of computing the Haversine distance for every open store.
CREATE INDEX ix_stores_open_lat_lng ON stores (lat, lng) WHERE is_open;

-- Store inbox: newest-first per store, read through request_target(store_id) then order_requests by PK;
-- ix_target_store (V1) already covers it. This one covers the FK quote_items -> request_items, which had
-- no index (used by ON DELETE CASCADE and by joins from a request item to its quote lines).
CREATE INDEX ix_qitem_request_item ON quote_items (request_item_id);
