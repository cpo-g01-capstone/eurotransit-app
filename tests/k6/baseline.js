// EuroTransit — baseline load (T9 / EM-26).
//
// Steady checkout traffic against the gateway, with thresholds encoding the
// RATIFIED SLOs (docs/design/slo-definitions.md, config repo):
//   - latency:  p95 of POST /orders < 500ms
//   - success:  >= 99.5% non-5xx, where HTTP 429 counts as SUCCESS by design
//               (load shedding is a controlled refusal, not a failure).
//
// Also the tool that validates the SLO numbers empirically (D2) and tunes the
// ADR 0018 breaker knobs. Run via `just load-baseline` (BASE_URL/VUS/DURATION env).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';
// '' when hitting the orders service directly (local dev); '/api' through Traefik.
const API = __ENV.API_PREFIX !== undefined ? __ENV.API_PREFIX : '/api';
// The seeded 100-seat demo route (V2__seed_demo_routes.sql).
const ROUTE_ID = __ENV.ROUTE_ID || '00000000-0000-0000-0000-000000000001';

export const options = {
  vus: Number(__ENV.VUS || 3),
  duration: __ENV.DURATION || '3m',
  thresholds: {
    'http_req_duration{endpoint:place_order}': ['p(95)<500'], // latency SLO
    checkout_success: ['rate>0.995'],                          // success SLO
  },
};

const success = new Rate('checkout_success');
const shed = new Rate('shed_429');

export default function () {
  // Unique per attempt: we are NOT testing idempotency replay here (the E2E does).
  const key = `k6-base-${__VU}-${__ITER}-${Date.now()}`;

  const res = http.post(
    `${BASE}${API}/orders`,
    JSON.stringify({ routeId: ROUTE_ID, seats: 1 }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      tags: { endpoint: 'place_order' },
    },
  );

  shed.add(res.status === 429);
  // SLO judgment: 5xx = failure; 429 = deliberate backpressure = success.
  success.add(res.status < 500);
  check(res, {
    'placed (202) / replay (200) / shed (429)': (r) => [200, 202, 429].includes(r.status),
  });

  if (res.status === 202) {
    const id = res.json('orderId');
    const g = http.get(`${BASE}${API}/orders/${id}`, { tags: { endpoint: 'get_order' } });
    check(g, { 'order readable': (r) => r.status === 200 });
  }

  sleep(1);
}
