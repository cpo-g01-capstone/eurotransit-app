// EuroTransit — end-to-end checkout (EM-26).
//
// Follows each order through the WHOLE money path: POST returns 202 (DRAFT),
// then the Kafka pipeline reserves seats, the synchronous breaker-wrapped call
// authorizes the payment, and the order converges to CONFIRMED. This script
// polls GET /orders/{id} until a terminal state and measures:
//   - e2e_converted:     did the order reach CONFIRMED? (steady state: ~100%)
//   - e2e_confirm_time:  how long the async pipeline took end-to-end
//
// This is also the "steady state" driver for the chaos experiments: run it
// during CE-1/CE-2/CE-4/CE-5 and watch conversion/latency on the dashboards.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';
const API = __ENV.API_PREFIX !== undefined ? __ENV.API_PREFIX : '/api';
const ROUTE_ID = __ENV.ROUTE_ID || '00000000-0000-0000-0000-000000000001';
const POLL_TIMEOUT_S = Number(__ENV.POLL_TIMEOUT_S || 45); // > worst-case redelivery ladder (~32s), adversarial audit / #19

export const options = {
  vus: Number(__ENV.VUS || 2),
  duration: __ENV.DURATION || '2m',
  thresholds: {
    e2e_converted: ['rate>0.99'], // steady state; EXPECTED to dip during chaos windows
  },
};

const converted = new Rate('e2e_converted');
const confirmTime = new Trend('e2e_confirm_time', true);

export default function () {
  const key = `k6-e2e-${__VU}-${__ITER}-${Date.now()}`;
  const placed = http.post(
    `${BASE}${API}/orders`,
    JSON.stringify({ routeId: ROUTE_ID, seats: 1 }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
      tags: { endpoint: 'place_order' } },
  );

  if (placed.status === 429) { sleep(1); return; } // shed: skip, not a conversion failure
  if (!check(placed, { 'placed 202': (r) => r.status === 202 })) {
    converted.add(false);
    sleep(1);
    return;
  }

  const id = placed.json('orderId');
  const start = Date.now();
  let status = 'DRAFT';

  while (Date.now() - start < POLL_TIMEOUT_S * 1000) {
    const g = http.get(`${BASE}${API}/orders/${id}`, { tags: { endpoint: 'poll_order' } });
    if (g.status === 200) {
      status = g.json('status');
      if (status === 'CONFIRMED' || status === 'FAILED') break;
    }
    sleep(0.5);
  }

  const ok = status === 'CONFIRMED';
  converted.add(ok);
  if (ok) confirmTime.add(Date.now() - start);
  check(null, { [`order terminal (got ${status})`]: () => ok || status === 'FAILED' });

  sleep(1);
}
