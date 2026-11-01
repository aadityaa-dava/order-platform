CREATE TABLE orders (
    id                  UUID         PRIMARY KEY,
    customer_id         VARCHAR(64)  NOT NULL,
    customer_email      VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    total_amount_minor  BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,
    correlation_id      UUID         NOT NULL,
    failure_reason      VARCHAR(255),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_orders_total  CHECK (total_amount_minor >= 0)
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id                BIGSERIAL    PRIMARY KEY,
    order_id          UUID         NOT NULL REFERENCES orders (id),
    sku               VARCHAR(64)  NOT NULL,
    quantity          INTEGER      NOT NULL,
    unit_price_minor  BIGINT       NOT NULL,
    CONSTRAINT chk_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_items_price    CHECK (unit_price_minor >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

-- Transactional outbox (ADR-0002): written in the same transaction as the business change.
CREATE TABLE outbox (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    topic         VARCHAR(128) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

-- The poller only ever reads unpublished rows, so index only those.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- Consumer-side deduplication (ADR-0003).
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Idempotency for POST /orders (client-supplied Idempotency-Key header).
CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(128) PRIMARY KEY,
    request_hash     CHAR(64)     NOT NULL,
    order_id         UUID         NOT NULL REFERENCES orders (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
