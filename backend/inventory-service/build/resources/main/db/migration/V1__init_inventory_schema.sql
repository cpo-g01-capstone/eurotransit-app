-- Inventory service — initial schema (EM-25)
-- Applied by Flyway on startup. Do not edit once merged; add V2__ for changes.

CREATE TABLE routes (
    id               UUID PRIMARY KEY,
    origin           VARCHAR(255) NOT NULL,
    destination      VARCHAR(255) NOT NULL,
    departure_time   TIMESTAMPTZ  NOT NULL,
    total_seats      INT          NOT NULL,
    available_seats  INT          NOT NULL,
    price            DECIMAL(10,2) NOT NULL,
    version          INT          NOT NULL DEFAULT 0
);

CREATE TABLE reservations (
    id          UUID PRIMARY KEY,
    order_id    UUID         NOT NULL,
    route_id    UUID         NOT NULL REFERENCES routes(id),
    seats       INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'RESERVED',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Prevents double-reservation for the same order+route at DB level
CREATE UNIQUE INDEX idx_reservations_order_route ON reservations(order_id, route_id);

-- Kafka consumer deduplication (money-path events). Key = composite of order id + event type.
CREATE TABLE processed_events (
    event_id      VARCHAR(512) PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_routes_available ON routes (available_seats) WHERE available_seats > 0;
CREATE INDEX idx_reservations_order_id ON reservations (order_id);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
