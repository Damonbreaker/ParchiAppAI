-- Collaborative group grocery lists (shareable WhatsApp link).

CREATE TABLE shared_lists (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token           VARCHAR(24)  NOT NULL,
    owner_id        BIGINT       NOT NULL REFERENCES users (id),
    title           VARCHAR(80)  NOT NULL DEFAULT 'Group list',
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    expires_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_shared_lists_token UNIQUE (token),
    CONSTRAINT ck_shared_lists_status CHECK (status IN ('OPEN', 'LOCKED', 'PLACED', 'EXPIRED'))
);

CREATE INDEX ix_shared_lists_owner ON shared_lists (owner_id);
CREATE INDEX ix_shared_lists_expires ON shared_lists (expires_at);

CREATE TABLE shared_list_items (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shared_list_id   BIGINT         NOT NULL REFERENCES shared_lists (id) ON DELETE CASCADE,
    name             VARCHAR(60)    NOT NULL,
    quantity         NUMERIC(10, 3) NOT NULL,
    unit             VARCHAR(10)    NOT NULL,
    position         INT            NOT NULL DEFAULT 0,
    guest_key        VARCHAR(64),
    added_by_label   VARCHAR(40)    NOT NULL DEFAULT 'Friend',
    added_by_user_id BIGINT         REFERENCES users (id),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_shared_item_qty CHECK (quantity > 0),
    CONSTRAINT ck_shared_item_unit CHECK (unit IN ('PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN'))
);

CREATE INDEX ix_shared_list_items_list ON shared_list_items (shared_list_id, position);
