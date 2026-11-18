-- Phase 5: accept, ready, picked up. Never edit V1/V2.
ALTER TABLE order_requests
    ADD COLUMN accepted_at  TIMESTAMPTZ,
    ADD COLUMN ready_at     TIMESTAMPTZ,
    ADD COLUMN picked_up_at TIMESTAMPTZ,
    ADD COLUMN pickup_code  VARCHAR(4),
    ADD CONSTRAINT ck_req_pickup_code CHECK (pickup_code ~ '^[0-9]{4}$');
