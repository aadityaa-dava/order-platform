CREATE TABLE payments (
    id                 UUID         PRIMARY KEY,
    order_id           UUID         NOT NULL UNIQUE,
    customer_id        VARCHAR(64)  NOT NULL,
    amount_minor       BIGINT       NOT NULL,
    currency           CHAR(3)      NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    failure_reason     VARCHAR(255),
    gateway_reference  VARCHAR(128),
    attempts           INTEGER      NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_payments_status CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT chk_payments_amount CHECK (amount_minor >= 0)
);

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
