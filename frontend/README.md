# EuroTransit Frontend

React SPA for the EuroTransit train-ticket marketplace (Cloud Programming & Operations
capstone, PoliTO 2025-26). Design inspired by italotreno.com. Every element in this UI is
backed by a real backend call — there are no decorative features, and no backend
capability is left unexposed.

## Run

```bash
npm install
npm run dev          # http://localhost:5173 — /api proxied to the live cluster
npm test             # API-layer unit tests (idempotency, 429 backoff, polling contract)
npm run typecheck    # tsc, strict mode
npm run lint         # oxlint incl. XSS-sink rules (no-eval, react/no-danger, no-script-url)
npm run build        # production build in dist/
```

Dev proxy target: `VITE_DEV_PROXY_TARGET` in `.env` (defaults to
`https://eurotransit.vojtechn.dev`). It is a public URL — never put secrets in `VITE_*`
vars, they are compiled into the public bundle.

## What maps to what (full backend coverage)

| Backend | UI |
|---|---|
| `GET /api/catalog` (catalog-service) | Home departures board, Trains search/results (client-side filter — the API has no query params) |
| `GET /api/catalog/{id}` | Route page with live availability, sold-out state |
| `POST /api/orders` + mandatory `Idempotency-Key` (orders-service) | Checkout panel; fresh `crypto.randomUUID()` key per attempt, reused across automatic 429 retries so the backend replays instead of double-booking |
| `429 Retry-After` load shedding (Resilience4j, 50 req/s) | Visible "high demand, retrying…" state with bounded backoff |
| `GET /api/orders/{id}` polling, `DRAFT→RESERVED→CONFIRMED/FAILED` | Order page rail-line timeline, 800 ms poll, stall warning at 45 s (payment redelivery ladder ≈ 32 s) |
| inventory-service (Kafka-only, no REST) | "Seats reserved" station on the timeline; advisory-availability hints |
| payments-service (internal `/authorize`, never called by the browser) | "Payment authorised" station; EUR totals |
| notifications-service (Kafka-only) | "Confirmation sent" station on the confirmed state |
| No list-orders endpoint, no accounts | **My trips**: order IDs remembered in this browser (localStorage — IDs only, never tokens), status re-fetched live; manual order-ID lookup with UUID validation |

The backend intentionally has **no authentication** (it is a resilience-focused system),
so this app deliberately ships no login. If auth is ever added server-side: access token
in memory, refresh token in an httpOnly cookie — never localStorage (07b).

## 07b-Frontend Security checklist

| Check | Where |
|---|---|
| XSS: JSX escaping only; no `dangerouslySetInnerHTML`, `eval`, `javascript:` URLs | Enforced by `.oxlintrc.json` (`react/no-danger`, `no-eval`, `no-new-func`, `no-script-url`) |
| Untrusted input validated before use | Order/route IDs must match a UUID regex before any fetch/navigation (`src/lib/uuid.ts`, router loaders); passenger/seat counts clamped (`clampPax`, `Stepper`) — and the server re-validates everything |
| Token storage | N/A by design: no tokens exist. localStorage holds only order UUIDs (`src/store/trips.ts`) |
| CSRF | No session cookies exist; mutating requests are same-origin JSON POSTs |
| CORS | Avoided entirely: SPA and API share one origin behind Traefik; dev uses the Vite proxy |
| CSP without `unsafe-inline` script-src, set by the server | `deploy/nginx.conf` (enforced in prod); `vite preview` enforces the same header on the built app; dev server sends it Report-Only (HMR needs inline) — zero violations in the browser run |
| HSTS | `deploy/nginx.conf` (`max-age=31536000; includeSubDomains`); Traefik already redirects HTTP→HTTPS |
| No secrets in `VITE_*` | Only `VITE_DEV_PROXY_TARGET` (public URL); see `.env.example` |
| Supply chain | `package-lock.json` committed; `npm audit` clean; no CDN scripts (fonts self-hosted via @fontsource, so no SRI surface); minimal deps — no UI kit, primitives are hand-written |

## Architecture

```
src/
  api/        typed client — the single audited network boundary
              (types.ts mirrors the Kotlin DTOs; orders.ts owns idempotency + 429 backoff)
  features/   catalog (search/cards), checkout (panel state machine),
              orders (poller hook + saga timeline)
  components/ ui primitives (button/badge/card/select/stepper/spinner), layout shell
  pages/      Home, Trains, RouteDetail, Order, Trips, errors
  store/      trips.ts — localStorage order memory (IDs only)
  routes.tsx  React Router config; loaders validate URL params (guard pattern)
deploy/nginx.conf   production static server with CSP/HSTS
docs/               design document
```
