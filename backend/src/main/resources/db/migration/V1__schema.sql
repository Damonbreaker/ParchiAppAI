-- V1: initial schema for the Kirana Parchi App (see PLAN.md "Data model").
-- Never edit this file once applied; add V3__..., V4__... instead.

-- ---------------------------------------------------------------- users
CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    phone          VARCHAR(15)  NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    preferred_lang VARCHAR(2)   NOT NULL DEFAULT 'en',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_phone UNIQUE (phone),
    CONSTRAINT ck_users_role  CHECK (role IN ('CUSTOMER', 'STORE_OWNER')),
    CONSTRAINT ck_users_lang  CHECK (preferred_lang IN ('en', 'hi'))
);

-- ---------------------------------------------------------------- stores
CREATE TABLE stores (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id   BIGINT           NOT NULL REFERENCES users (id),
    name       VARCHAR(120)     NOT NULL,
    address    VARCHAR(255)     NOT NULL,
    area       VARCHAR(100)     NOT NULL,
    phone      VARCHAR(15)      NOT NULL,
    lat        DOUBLE PRECISION NOT NULL,
    lng        DOUBLE PRECISION NOT NULL,
    is_open    BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_stores_owner UNIQUE (owner_id),          -- one store per owner for now
    CONSTRAINT ck_stores_lat   CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_stores_lng   CHECK (lng BETWEEN -180 AND 180)
);

CREATE INDEX ix_stores_name_area ON stores (name, area);
CREATE INDEX ix_stores_open      ON stores (is_open);

-- ---------------------------------------------------------------- order_requests
CREATE TABLE order_requests (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id       BIGINT           NOT NULL REFERENCES users (id),
    lat               DOUBLE PRECISION NOT NULL,
    lng               DOUBLE PRECISION NOT NULL,
    image_path        VARCHAR(255),
    source            VARCHAR(10)      NOT NULL,
    mode              VARCHAR(10)      NOT NULL,
    status            VARCHAR(20)      NOT NULL DEFAULT 'DRAFT',
    accepted_quote_id BIGINT,                              -- FK added after quotes exists
    version           BIGINT           NOT NULL DEFAULT 0, -- JPA @Version
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_req_source CHECK (source IN ('PHOTO', 'MANUAL')),
    CONSTRAINT ck_req_mode   CHECK (mode IN ('NEAREST', 'DIRECT')),
    CONSTRAINT ck_req_status CHECK (status IN ('DRAFT', 'OPEN', 'ACCEPTED', 'READY', 'PICKED_UP', 'CANCELLED'))
);

CREATE INDEX ix_req_customer_created ON order_requests (customer_id, created_at DESC);

-- ---------------------------------------------------------------- request_items
CREATE TABLE request_items (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id BIGINT        NOT NULL REFERENCES order_requests (id) ON DELETE CASCADE,
    name       VARCHAR(120)  NOT NULL,                     -- Hindi or English, UTF-8
    quantity   NUMERIC(10,3) NOT NULL,
    unit       VARCHAR(10)   NOT NULL,
    position   INT           NOT NULL,
    CONSTRAINT ck_ritem_qty  CHECK (quantity > 0),
    CONSTRAINT ck_ritem_unit CHECK (unit IN ('PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN')),
    CONSTRAINT uq_ritem_pos  UNIQUE (request_id, position)
);

-- ---------------------------------------------------------------- request_target
CREATE TABLE request_target (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id  BIGINT       NOT NULL REFERENCES order_requests (id) ON DELETE CASCADE,
    store_id    BIGINT       NOT NULL REFERENCES stores (id),
    distance_km NUMERIC(6,2) NOT NULL,
    seen_at     TIMESTAMPTZ,
    CONSTRAINT uq_target_request_store UNIQUE (request_id, store_id)
);

CREATE INDEX ix_target_store ON request_target (store_id);

-- ---------------------------------------------------------------- quotes
CREATE TABLE quotes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id        BIGINT        NOT NULL REFERENCES order_requests (id) ON DELETE CASCADE,
    store_id          BIGINT        NOT NULL REFERENCES stores (id),
    total_price       NUMERIC(10,2) NOT NULL,              -- computed on the server from quote_items
    packing_time_mins INT           NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'SUBMITTED',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_quote_request_store UNIQUE (request_id, store_id),
    CONSTRAINT ck_quote_total   CHECK (total_price >= 0),
    CONSTRAINT ck_quote_packing CHECK (packing_time_mins >= 0),
    CONSTRAINT ck_quote_status  CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED'))
);

CREATE INDEX ix_quote_store ON quotes (store_id);

ALTER TABLE order_requests
    ADD CONSTRAINT fk_req_accepted_quote FOREIGN KEY (accepted_quote_id) REFERENCES quotes (id);

-- ---------------------------------------------------------------- quote_items
CREATE TABLE quote_items (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quote_id        BIGINT        NOT NULL REFERENCES quotes (id) ON DELETE CASCADE,
    request_item_id BIGINT        NOT NULL REFERENCES request_items (id) ON DELETE CASCADE,
    offered_qty     NUMERIC(10,3) NOT NULL,
    unit            VARCHAR(10)   NOT NULL,
    line_price      NUMERIC(10,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    CONSTRAINT uq_qitem_quote_item UNIQUE (quote_id, request_item_id),
    CONSTRAINT ck_qitem_qty    CHECK (offered_qty >= 0),
    CONSTRAINT ck_qitem_price  CHECK (line_price >= 0),
    CONSTRAINT ck_qitem_unit   CHECK (unit IN ('PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN')),
    CONSTRAINT ck_qitem_status CHECK (status IN ('AVAILABLE', 'PARTIAL', 'UNAVAILABLE'))
);
