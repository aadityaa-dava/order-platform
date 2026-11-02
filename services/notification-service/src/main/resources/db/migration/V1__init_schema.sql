CREATE TABLE notifications (
    id          UUID          PRIMARY KEY,
    order_id    UUID          NOT NULL,
    type        VARCHAR(32)   NOT NULL,
    recipient   VARCHAR(255)  NOT NULL,
    subject     VARCHAR(255)  NOT NULL,
    body        TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- business-level guard: at most one notification per order and outcome
    CONSTRAINT uq_notifications_order_type UNIQUE (order_id, type),
    CONSTRAINT chk_notifications_type CHECK (type IN ('ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_CANCELLED'))
);

-- Consumer-side deduplication (ADR-0003).
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
