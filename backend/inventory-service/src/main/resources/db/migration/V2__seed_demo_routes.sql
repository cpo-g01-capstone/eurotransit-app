-- Demo/dev seed: deterministic route ids so load tests (tests/k6, app repo) and
-- chaos experiments (CE-2) can target known routes without discovery calls.
-- ON CONFLICT keeps the migration idempotent and re-runnable across environments.
--
--   ...0001  "big" route   (100 seats) — baseline / e2e traffic
--   ...00ce  "tiny" route  (2 seats)   — CE-2 contention: more buyers than seats
INSERT INTO routes (id, origin, destination, departure_time, total_seats, available_seats, price, version)
VALUES
  ('00000000-0000-0000-0000-000000000001', 'Turin', 'Milan',  NOW() + INTERVAL '7 days', 100, 100, 19.90, 0),
  ('00000000-0000-0000-0000-0000000000ce', 'Rome',  'Naples', NOW() + INTERVAL '7 days',   2,   2, 24.50, 0)
ON CONFLICT (id) DO NOTHING;
