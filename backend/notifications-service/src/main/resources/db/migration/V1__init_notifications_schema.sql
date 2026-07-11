-- Notifications service — initial schema.
-- Applied by Flyway on startup. Do not edit once merged; add V2__ for changes.
-- status uses VARCHAR + CHECK (not a PG ENUM) to avoid R2DBC enum-codec complexity.

CREATE TABLE sent_notifications (
    order_id    VARCHAR(255) PRIMARY KEY,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
