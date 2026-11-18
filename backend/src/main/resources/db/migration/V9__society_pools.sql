-- Recognised societies / towers + timed anonymous delivery pools.

CREATE TABLE communities (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(24)  NOT NULL,
    society_name VARCHAR(80)  NOT NULL,
    tower_name   VARCHAR(80)  NOT NULL,
    label        VARCHAR(120) NOT NULL,
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_communities_code UNIQUE (code)
);

CREATE TABLE community_members (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    alias        VARCHAR(40)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_member UNIQUE (community_id, user_id),
    CONSTRAINT uq_community_alias UNIQUE (community_id, alias)
);

CREATE INDEX ix_community_members_user ON community_members (user_id);

CREATE TABLE order_pools (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token             VARCHAR(24)  NOT NULL,
    community_id      BIGINT       NOT NULL REFERENCES communities (id),
    host_user_id      BIGINT       NOT NULL REFERENCES users (id),
    title             VARCHAR(80)  NOT NULL DEFAULT 'Tower delivery pool',
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    window_minutes    INT          NOT NULL DEFAULT 12,
    opens_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closes_at         TIMESTAMPTZ  NOT NULL,
    order_request_id  BIGINT       REFERENCES order_requests (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_pools_token UNIQUE (token),
    CONSTRAINT ck_order_pools_status CHECK (status IN (
        'OPEN', 'CLOSED', 'QUOTING', 'ACCEPTED', 'READY', 'DONE', 'EXPIRED', 'CANCELLED'
    )),
    CONSTRAINT ck_order_pools_window CHECK (window_minutes BETWEEN 5 AND 30)
);

CREATE INDEX ix_order_pools_community ON order_pools (community_id, status);
CREATE INDEX ix_order_pools_host ON order_pools (host_user_id);
CREATE INDEX ix_order_pools_request ON order_pools (order_request_id);

CREATE TABLE pool_members (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id         BIGINT       NOT NULL REFERENCES order_pools (id) ON DELETE CASCADE,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    alias           VARCHAR(40)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'JOINED',
    from_photo      BOOLEAN      NOT NULL DEFAULT FALSE,
    pickup_code     VARCHAR(4),
    approved_at     TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_pool_member UNIQUE (pool_id, user_id),
    CONSTRAINT uq_pool_member_alias UNIQUE (pool_id, alias),
    CONSTRAINT ck_pool_member_status CHECK (status IN (
        'JOINED', 'LIST_READY', 'APPROVED', 'DELIVERED', 'LEFT'
    ))
);

CREATE INDEX ix_pool_members_pool ON pool_members (pool_id);

CREATE TABLE pool_member_items (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_member_id BIGINT         NOT NULL REFERENCES pool_members (id) ON DELETE CASCADE,
    name           VARCHAR(60)    NOT NULL,
    quantity       NUMERIC(10, 3) NOT NULL,
    unit           VARCHAR(10)    NOT NULL,
    position       INT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_pool_item_qty CHECK (quantity > 0),
    CONSTRAINT ck_pool_item_unit CHECK (unit IN ('PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN'))
);

CREATE INDEX ix_pool_member_items_member ON pool_member_items (pool_member_id, position);
