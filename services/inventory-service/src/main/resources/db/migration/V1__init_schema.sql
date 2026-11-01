-- available + reserved = units on hand. Reserving moves units from available to reserved.
CREATE TABLE stock (
    sku         VARCHAR(64)  PRIMARY KEY,
    available   INTEGER      NOT NULL,
    reserved    INTEGER      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_stock_available CHECK (available >= 0),
    CONSTRAINT chk_stock_reserved  CHECK (reserved >= 0)
);

-- One reservation per order; UNIQUE(order_id) is a second line of defence behind event dedupe.
CREATE TABLE reservations (
    id          UUID         PRIMARY KEY,
    order_id    UUID         NOT NULL UNIQUE,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'RELEASED'))
);

CREATE TABLE reservation_items (
    id              BIGSERIAL    PRIMARY KEY,
    reservation_id  UUID         NOT NULL REFERENCES reservations (id),
    sku             VARCHAR(64)  NOT NULL REFERENCES stock (sku),
    quantity        INTEGER      NOT NULL,
    CONSTRAINT chk_reservation_items_qty CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_items_reservation ON reservation_items (reservation_id);

-- Transactional outbox (ADR-0002).
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

CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- Consumer-side deduplication (ADR-0003).
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
