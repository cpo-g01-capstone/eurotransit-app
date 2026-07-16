-- Optional per-order notification contact (email). NOT a customer identity:
-- nullable, carried to the order-confirmed event so Notifications can address
-- the real recipient. A NULL contact falls back to the demo default downstream.
ALTER TABLE orders
    ADD COLUMN customer_contact VARCHAR(320);
