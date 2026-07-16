# Customer email + real SMTP notification — design

**Date:** 2026-07-16
**Status:** Approved (design), pending implementation plan
**Scope:** `eurotransit-app` (frontend + orders + notifications) with a companion PR in `eurotransit-config`.

## Problem

The notifications service works (consumes `order-confirmed`, dedups, "sends") but is
**invisible and fake** from the user's perspective, so it can't be exercised through the
frontend during the demo:

1. The checkout collects only seats + card — there is no email input, and
   `CreateOrderRequest(routeId, seats)` has no contact field.
2. Orders publishes `OrderConfirmedEvent(orderId, timestamp)` — no recipient. Notifications
   falls back to the hardcoded default `customer@demo.eurotransit.test`
   (`OrderConfirmedEvent.customerContact` default).
3. The "send" is a `log.info` + a `notifications_sent_total` counter (`LoggingEmailSender`) —
   no real email, nothing surfaced back to the UI.

**Goal:** let the user type an email at checkout, carry it to notifications, send a **real**
email (Mailtrap sandbox), and make the notification **demonstrable from the frontend** — without
creating a new service and without introducing user/account management.

## Guiding principles (assignment alignment)

- **Per-order notification contact, not a user identity.** No login, no managed customer in the
  domain. Consistent with "no user management" and "notifications = best-effort, degradable".
- **Optional end-to-end.** The `customerContact` field stays nullable/defaulted at every layer.
  This preserves the resilience contract: an event without a contact still deserializes and is
  handled (the original bug — a *required* field sent every real event to the DLT — must not
  return).
- **A bad or missing email must NEVER fail an order.** Orders does not block on email validation;
  an SMTP failure degrades only the *notification* (retry → DLT), the order stays CONFIRMED.

## Field naming

`customerContact` is used across the API DTO, the DB column (`customer_contact`), and the Kafka
event — aligning with the existing field in the notifications `OrderConfirmedEvent`. The frontend
labels it **"Email (optional)"** in the UI.

## Design

### 1. Frontend (`frontend/`)

- **`features/checkout/CheckoutPanel.tsx`** — add an optional **"Email (optional)"** input in the
  payment step. Lenient validation: show an inline error only when the field is non-empty and
  malformed; an empty field proceeds normally. Include the trimmed value in the `placeOrder` call.
- **`api/types.ts`** — `CreateOrderRequest` gains `customerContact?: string`;
  `OrderResponse` gains `customerContact?: string`.
- **`api/orders.ts`** — pass `customerContact` in the POST body.
- **`pages/OrderPage.tsx` / `features/orders/SagaTimeline.tsx`** — when the order reaches
  **CONFIRMED**, render a line such as *"Confirmation email on its way to you@email.com"*, sourced
  from `GET /orders/{id}` (`customerContact`) — a real round-trip through the backend, not just
  local state. **Copy stays honest:** "on its way / sent", never "delivered" (notifications is
  async/best-effort). The definitive proof is the Mailtrap inbox shown on screen.

### 2. Orders service (`backend/orders-service`)

- **`event/OrderEvents.kt`**
  - `CreateOrderRequest`: add `customerContact: String? = null`.
  - `OrderConfirmedEvent`: add `customerContact: String? = null`.
  - `OrderResponse`: add `customerContact: String? = null`.
- **Persistence** — new **nullable** column `customer_contact` via the next Flyway migration
  (`V5__add_customer_contact.sql`; V1–V4 exist, V5 is next). Add the field to `model/Order.kt`
  and the repository mapping.
- **`service/OrderService.kt`** — normalize the incoming value (trim → null if blank; **no reject
  on malformed email**) and persist it on the order at creation. The idempotency cache captures
  it at first write; replays return the cached `OrderResponse`.
- **`kafka/OrderKafkaConsumer.kt`** — where it publishes `OrderConfirmedEvent(orderId = ...)`
  after the RESERVED→CONFIRMED transition, re-read the order's `customerContact` and include it:
  `OrderConfirmedEvent(orderId, customerContact)`.
- **`controller/OrderController.kt`** — `getOrder` returns `customerContact` in the response.

### 3. Notifications service (`backend/notifications-service`)

- **`build.gradle.kts`** — add `spring-boot-starter-mail`.
- **`email/SmtpEmailSender.kt`** — new `EmailSender` implementation using `JavaMailSender`. Sends
  a real confirmation email to `event.customerContact` with a **connect timeout and read timeout**
  set from properties, so a slow SMTP server actually exercises the retry → DLT path rather than
  hanging the consumer.
- **Selection** — keep `LoggingEmailSender` as the default; select the active sender with
  `@ConditionalOnProperty(name = "notifications.email.sender")` (`smtp` vs `logging`,
  `logging` when unset). Tests and resilience runs keep the stub; the demo/cluster runs SMTP.
- **Semantics unchanged** — a send failure still throws → Kafka error handler retries → DLT. SMTP
  is now a **real degradable external dependency** (a candidate for a future chaos experiment).
- **`OrderConfirmedEvent`** (notifications side) — keep the defaulted `customerContact` for
  backward compatibility; real events now carry the true recipient.

### 4. Config repo (`eurotransit-config`) — companion PR

- Mailtrap SMTP credentials as a **SealedSecret**, injected as env into the notifications
  Deployment: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
  `SPRING_MAIL_PASSWORD`, `NOTIFICATIONS_EMAIL_SENDER=smtp`, plus the mail timeouts.
- **Threat-model note:** no plaintext secret (SealedSecret only); egress limited to the SMTP host.

## Data flow (all optional/nullable)

```
Checkout (email, optional)
  → POST /orders  CreateOrderRequest.customerContact
  → orders.customer_contact (persisted, nullable)
  → [saga: placed → reserved → payment-authorized → CONFIRMED]
  → OrderConfirmedEvent(orderId, customerContact)
  → notifications: SmtpEmailSender.send() → Mailtrap  (default addr if null)
GET /orders/{id}.customerContact → frontend "confirmation on its way to …"
```

## Testing

- **Frontend** — unit test the email validation (empty ok, malformed flagged) and that the
  CONFIRMED view renders the recipient from the API response.
- **Orders** — persistence of `customer_contact` (incl. null); `OrderConfirmedEvent` carries the
  stored value; a malformed email never fails order creation.
- **Notifications** — `SmtpEmailSender` selected only under the property; send failure/timeout is
  rethrown (existing retry→DLT tests keep the `LoggingEmailSender` stub). Do not add a live-SMTP
  test to CI — assert wiring/timeout config, not real delivery.

## Out of scope / non-goals

- No login, accounts, or customer directory. No email verification. No unsubscribe.
- No cross-service "notification status" API in orders — order CONFIRMED is not a delivery
  guarantee, and notifications is best-effort by design.

## Delivery notes

- Migration number: V5 (verified V1–V4 present).
- `docs/agent-log.md`: log this as an AI-assisted change to the `order-confirmed` event contract
  that passed the PR gate (AI usage policy).
- The `eurotransit-config` changes ship as a separate, reviewed PR (SealedSecret + Deployment env).
