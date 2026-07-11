-- V2__add_order_details added columns for a richer Order model (customer, seat
-- class, pricing) that was never implemented: the Order entity has always been
-- id/status/created_at/updated_at, and four of those columns are NOT NULL with
-- no default — every INSERT into orders was doomed to fail. The table is empty
-- in every environment (the money path never wrote a row), so dropping them is
-- safe. If the richer model ever lands, reintroduce the columns together with
-- the entity change in the same PR.
ALTER TABLE orders
    DROP COLUMN customer_id,
    DROP COLUMN route_id,
    DROP COLUMN seat_class,
    DROP COLUMN quantity,
    DROP COLUMN total_amount,
    DROP COLUMN failure_reason,
    DROP COLUMN version;
