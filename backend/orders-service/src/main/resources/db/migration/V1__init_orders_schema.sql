-- Orders service — initial schema (EM-16)
-- Applied by Flyway on startup. Do not edit once merged; add V2__ for changes.

CREATE TYPE order_status AS ENUM (
    'DRAFT',
    'RESERVED',
    'PAID',
    'CONFIRMED',
    'FAILED'
);

CREATE TABLE orders (
    id          UUID PRIMARY KEY,
    status      order_status NOT NULL DEFAULT 'DRAFT',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Kafka consumer deduplication (money-path events). Key = composite of order id + event type.
CREATE TABLE processed_events (
    event_id      VARCHAR(512) PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Synchronous idempotency (e.g. POST /orders). Insert-first pattern; see docs/design/idempotency.md.
CREATE TABLE idempotency_records (
    idempotency_key  VARCHAR(255) PRIMARY KEY,
    response_payload JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
