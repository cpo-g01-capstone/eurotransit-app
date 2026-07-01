-- Payments service — initial schema (EM-25)
-- Applied by Flyway on startup. Do not edit once merged; add V2__ for changes.

CREATE TABLE payment_intents (
    id               UUID PRIMARY KEY,
    order_id         UUID          NOT NULL,
    amount           DECIMAL(10,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'EUR',
    status           VARCHAR(20)   NOT NULL DEFAULT 'AUTHORIZED',
    idempotency_key  VARCHAR(512)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Prevents double-charge for the same order at DB level
CREATE UNIQUE INDEX idx_payment_intents_order_id ON payment_intents(order_id);
CREATE UNIQUE INDEX idx_payment_intents_idempotency_key ON payment_intents(idempotency_key);

-- Kafka consumer deduplication (money-path events). Key = composite of order id + event type.
CREATE TABLE processed_events (
    event_id      VARCHAR(512) PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
