// EuroTransit — CE-2 contention driver (chaos experiment #2).
//
// Fires MORE buyers than seats at the tiny seeded route (2 seats, id ...00ce)
// while the operator kills the Inventory pod (`just chaos ce-2-pod-kill-inventory`,
// config repo). The script itself has NO pass/fail thresholds: the experiment's
// verdict comes from the DB invariants I1/I2/I3 (never oversell, seats reconcile,
// no duplicate reservations) checked via the SQL in the CE-2 report — this is
// just the load half of the scientific method.
//
// Expected in steady state: exactly SEATS_AVAILABLE orders CONFIRMED, the rest
// FAILED (sold out). Same expectation DURING the pod kill — that's the point.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';
const API = __ENV.API_PREFIX !== undefined ? __ENV.API_PREFIX : '/api';
// The seeded 2-seat contention route (V2__seed_demo_routes.sql) — matches the
// route id used throughout the CE-2 report and pre-test docs.
const ROUTE_ID = __ENV.ROUTE_ID || '00000000-0000-0000-0000-0000000000ce';
const POLL_TIMEOUT_S = Number(__ENV.POLL_TIMEOUT_S || 45);

export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 10),
      iterations: Number(__ENV.ORDERS || 20), // 20 buyers, 2 seats
      maxDuration: '3m',
    },
  },
};

const confirmed = new Counter('orders_confirmed');
const failed = new Counter('orders_failed');
const stuck = new Counter('orders_stuck_nonterminal');

export default function () {
  const key = `k6-ce2-${__VU}-${__ITER}-${Date.now()}`;
  const placed = http.post(
    `${BASE}${API}/orders`,
    JSON.stringify({ routeId: ROUTE_ID, seats: 1 }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      tags: { endpoint: 'place_order' } },
  );
  if (!check(placed, { 'placed 202': (r) => r.status === 202 })) return;

  const id = placed.json('orderId');
  const start = Date.now();
  let status = 'DRAFT';
  while (Date.now() - start < POLL_TIMEOUT_S * 1000) {
    const g = http.get(`${BASE}${API}/orders/${id}`, { tags: { endpoint: 'poll_order' } });
    if (g.status === 200) {
      status = g.json('status');
      if (status === 'CONFIRMED' || status === 'FAILED') break;
    }
    sleep(1); // generous polling: mid-kill, redelivery backoff can take a while
  }

  if (status === 'CONFIRMED') confirmed.add(1);
  else if (status === 'FAILED') failed.add(1);
  else stuck.add(1); // non-terminal after timeout → investigate (possible lost order)

  console.log(`order ${id}: ${status}`);
}

export function handleSummary(data) {
  const c = data.metrics.orders_confirmed ? data.metrics.orders_confirmed.values.count : 0;
  const f = data.metrics.orders_failed ? data.metrics.orders_failed.values.count : 0;
  const s = data.metrics.orders_stuck_nonterminal ? data.metrics.orders_stuck_nonterminal.values.count : 0;
  const line = `\nCE-2 driver summary: CONFIRMED=${c} FAILED(sold-out/compensated)=${f} STUCK=${s}\n` +
    `Now verify the DB invariants (I1/I2/I3) with the SQL in ce-2-pod-kill-inventory.md:\n` +
    `expected CONFIRMED == seats available at start; STUCK must be 0 after convergence.\n`;
  return { stdout: JSON.stringify(data, null, 0).slice(0, 0) + line };
}
