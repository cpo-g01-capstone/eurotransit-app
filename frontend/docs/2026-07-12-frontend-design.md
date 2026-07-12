# EuroTransit Frontend — Design Document

**Date:** 2026-07-12 · **Author:** Marco (with Claude Code)
**Scope:** SPA frontend for the EuroTransit capstone backend. Design inspired by italotreno.com.

## Constraints (from the request)

1. **Everything must work** — every UI element is backed by a real backend call. No decorative fake features.
2. **No omissions** — every backend capability the browser can reach (or observe) is surfaced.
3. **Follow the 07b-Frontend Security slides** (PoliTO, Malnati 2025-26) wherever applicable.

## Backend reality (verified 2026-07-12 against source + live cluster)

Browser-usable API (same-origin `/api/*` via Traefik at `https://eurotransit.vojtechn.dev`, `stripPrefix /api`):

| Endpoint | Notes |
|---|---|
| `GET /api/catalog` | Full route list, server-sorted by origin. No pagination/filters. `availableSeats` is advisory (eventually consistent). |
| `GET /api/catalog/{uuid}` | 200 `CatalogRoute` or 404 (empty body). |
| `POST /api/orders` | Header `Idempotency-Key` mandatory (non-blank). Body `{routeId: UUID, seats: Int}`. 202 = new (`status:"DRAFT"`), 200 = idempotent replay, 400 = missing key, 429 + `Retry-After: 1` = load-shed (retry with the SAME key). |
| `GET /api/orders/{uuid}` | `{orderId, status, message:""}`. Status ∈ DRAFT, RESERVED, CONFIRMED, FAILED. 404 for unknown. Not rate-limited. |

Not browser-facing: `POST /payments/authorize` (server-to-server, Idempotency-Key must equal orderId), inventory service (Kafka-only), notifications service (Kafka-only). Errors have **no body** (no problem+json) — the client keys off status codes only.

**Async saga the UI must reflect:** POST → 202 DRAFT → (Kafka: inventory reserves seats) RESERVED → (Orders calls Payments sync behind a circuit breaker) CONFIRMED → notifications email stub. Failure path: retries up to ~32s, then FAILED + seat release. Reference client polls every 0.5s, 45s timeout.

**No authentication exists in the backend.** Therefore this frontend has **no login/AuthContext/token layer — deliberately**. Adding one would violate constraint 1 (fake UI). The slide topics that presuppose auth (token storage, PKCE, CSRF-on-session-cookies) are documented as N/A with rationale; every other slide requirement is implemented (see Security section).

## Feature map (complete coverage, nothing invented)

| Backend capability | UI surface |
|---|---|
| Catalog list | **Home** hero search + **Trains** results page (client-side filter of the real list by origin/destination/passengers) |
| Catalog detail | **Route page** `/routes/:id` — live availability, price, sold-out state |
| Order creation + idempotency | **Checkout** on the route page: seat stepper (1..availableSeats), total price, `Idempotency-Key = crypto.randomUUID()` per attempt, kept across retries |
| 429 load-shedding | Automatic backoff (Retry-After) + visible "high demand, retrying…" state, same key reused |
| Order status polling / saga | **Order page** `/orders/:id` — live timeline: Placed (DRAFT) → Seats reserved (Inventory) → Payment authorised (Payments) → Confirmed + notification sent (Notifications); FAILED branch with seat-release explanation. Poll 800ms, stop on terminal state or 45s stall warning. |
| Order lookup | **My trips** `/orders` — order IDs saved client-side (localStorage: IDs only, never tokens — none exist), each re-fetched live; plus manual "find order by ID" form. |
| Advisory availability | Explicit "availability is indicative" hint (inventory is the source of truth) |

Explicitly **not** built: login/profile, seat maps, payment forms (payment is backend-internal), admin panels, notifications inbox — none exist in the backend.

## Architecture

- **Stack:** Vite + React 19 + TypeScript (strict) + React Router v7 (data mode: loaders + route guards pattern from the slides, adapted — guards validate params, not auth) + Tailwind CSS v4 + shadcn/ui-style components (Radix primitives) + lucide-react icons. No third-party scripts at runtime (no CDN, no analytics) → no SRI surface.
- **Layers:**
  - `src/api/` — typed client (`fetch`), DTO types mirroring Kotlin DTOs, status-code error mapping, 429 backoff, idempotency-key handling, order poller (single audited network boundary).
  - `src/features/` — catalog, checkout, orders (state machines for checkout/polling).
  - `src/components/ui/` — shadcn-style primitives.
  - `src/pages/` + `src/routes.tsx` — router config with loaders.
- **API origin:** same-origin `/api` always. Dev: Vite proxy → `https://eurotransit.vojtechn.dev` (or local backend via `VITE_DEV_PROXY_TARGET` — a URL, not a secret). Prod: static files served behind the same Traefik host, so SOP holds and CORS is unnecessary (per slides: same-origin beats CORS wildcards).

## Security (07b slide compliance)

| Slide requirement | Implementation |
|---|---|
| XSS: JSX escaping only; no `dangerouslySetInnerHTML`, no `eval`/`new Function`, no dynamic `href`/`src` from user input | Enforced; the only user-typed value used in navigation (order ID) is validated as UUID before use; ESLint rule forbids dangerous sinks |
| Token storage | N/A — backend has no auth; **nothing sensitive is ever stored**. localStorage holds only order UUIDs (documented) |
| CSRF | N/A for cookies (no session cookies exist); mutating POST carries JSON + custom header, and API is same-origin |
| CORS | Avoided entirely by same-origin `/api` (no `Access-Control-Allow-*` needed) |
| CSP without `unsafe-inline` script-src, set by server | `deploy/nginx.conf` ships CSP: `default-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` + `X-Content-Type-Options` etc. Vite dev server sends the same headers (report-only parity). |
| HSTS | Provided in nginx config for HTTPS deployments (`max-age=31536000; includeSubDomains`); Traefik already 301s HTTP→HTTPS |
| Client-side validation = UX, server = truth | Seat count (int, ≥1, ≤available), UUID formats validated client-side; all re-enforced server-side (idempotency key blank → 400 etc.) |
| No secrets in `VITE_*` | Only `VITE_DEV_PROXY_TARGET` (dev-only URL). CI-grepable. |
| Supply chain | `package-lock.json` committed; `npm audit` clean at ship time; minimal dependency set |

## Visual design (Italo-inspired, not a clone)

Deep crimson primary (#c8102e family) on near-black ink and warm off-white; bold condensed display type for destinations; hero with overlapping search card; route results as boarding-pass-style cards (origin→destination, big price, availability pill); saga timeline as a train-line stepper. Fonts self-hosted (CSP-safe). Fully responsive.

## Error handling

- 404 route/order → friendly not-found states.
- 429 on checkout → auto-retry with same key (max ~5 attempts), visible status.
- Network failure → retry affordances; polling tolerates transient errors.
- Stalled order (>45s non-terminal) → "still processing" warning with keep-waiting/check-later actions (order stays pollable).
- Sold-out route (`availableSeats == 0`) → booking disabled with explanation.

## Testing / verification

- `tsc --noEmit`, ESLint, production build.
- Vitest unit tests for the API layer (idempotency reuse, 429 backoff, poll terminal states) with mocked `fetch`.
- Live verification against `https://eurotransit.vojtechn.dev`: browse catalog, place a real order on the Rome→Naples route, watch it reach CONFIRMED/FAILED.
