-- Two-way ratings after pickup: customer↔store, with multi-select quality tags.

CREATE TABLE ratings (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id  BIGINT       NOT NULL REFERENCES order_requests (id) ON DELETE CASCADE,
    rater_id    BIGINT       NOT NULL REFERENCES users (id),
    target_type VARCHAR(20)  NOT NULL,
    store_id    BIGINT       REFERENCES stores (id),
    customer_id BIGINT       REFERENCES users (id),
    stars       INT          NOT NULL,
    qualities   TEXT         NOT NULL DEFAULT '[]',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rating_stars CHECK (stars BETWEEN 1 AND 5),
    CONSTRAINT ck_rating_target CHECK (target_type IN ('STORE', 'CUSTOMER')),
    CONSTRAINT ck_rating_store_target CHECK (
        (target_type = 'STORE' AND store_id IS NOT NULL AND customer_id IS NULL)
        OR (target_type = 'CUSTOMER' AND customer_id IS NOT NULL AND store_id IS NULL)
    ),
    CONSTRAINT uq_rating_once UNIQUE (request_id, rater_id)
);

CREATE INDEX ix_ratings_store ON ratings (store_id) WHERE store_id IS NOT NULL;
CREATE INDEX ix_ratings_customer ON ratings (customer_id) WHERE customer_id IS NOT NULL;
