CREATE TABLE IF NOT EXISTS outbox (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    topic         VARCHAR(128) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
CREATE TABLE IF NOT EXISTS processed_events (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
