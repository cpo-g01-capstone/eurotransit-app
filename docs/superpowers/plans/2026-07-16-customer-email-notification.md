# Customer email + real SMTP notification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user type an optional email at checkout, carry it through orders → the `order-confirmed` event → notifications, send a real email via SMTP (Mailtrap), and show the recipient on the confirmed order page.

**Architecture:** A per-order notification contact (`customerContact`), optional/nullable at every layer, persisted on the order row because `order-confirmed` is published later by the async saga. Notifications gains a real `SmtpEmailSender` selected by property, keeping the `LoggingEmailSender` stub as the default for tests. No new service, no user/account concept.

**Tech Stack:** Kotlin/Spring Boot 3 (WebFlux + R2DBC + Spring Kafka), Flyway, `spring-boot-starter-mail` (JavaMail), React + TypeScript + Vite (frontend), Mailtrap SMTP sandbox, Sealed Secrets (config repo).

## Global Constraints

- **`customerContact` is nullable/optional at EVERY layer.** A missing contact must still deserialize and be handled (notifications falls back to its default). Never make it required — a required field previously sent every real event to the DLT.
- **A bad or missing email must NEVER fail an order.** Orders performs no blocking email validation; order creation and the money path are unaffected by email problems.
- **Field name is `customerContact`** across DTO / DB column `customer_contact` / Kafka event. UI label is "Email (optional)".
- **Confirmation copy stays honest:** "on its way / sent", never "delivered" — notifications is async/best-effort.
- **Commits are performed by the human, not the AI** (app-repo `CLAUDE.md` forbids the AI running `git commit`/`git push`). Commit steps below are for whoever drives execution; if that is an AI agent in this repo, hand the commit to the user.
- **Do not add a live-SMTP test to CI.** Assert wiring/timeout config, not real delivery.
- **Package roots:** `com.eurotransit.orders`, `com.eurotransit.notifications`. Frontend uses the `@/` path alias.

---

### Task 1: Orders — persist an optional `customerContact` on the order

**Files:**
- Create: `backend/orders-service/src/main/resources/db/migration/V5__add_customer_contact.sql`
- Modify: `backend/orders-service/src/main/kotlin/com/eurotransit/orders/model/Order.kt`
- Modify: `backend/orders-service/src/main/kotlin/com/eurotransit/orders/event/OrderEvents.kt` (CreateOrderRequest)
- Modify: `backend/orders-service/src/main/kotlin/com/eurotransit/orders/service/OrderService.kt`
- Test: `backend/orders-service/src/test/kotlin/com/eurotransit/orders/persistence/OrderPersistenceIT.kt` (existing IT — add a case)

**Interfaces:**
- Produces: `Order(id, status, customerContact: String?, createdAt, updatedAt)`; `CreateOrderRequest(routeId, seats, customerContact: String? = null)`; `OrderService.placeOrder` persists the normalized contact.

- [ ] **Step 1: Write the failing test** — add to `OrderPersistenceIT.kt`, following the file's existing R2DBC/Testcontainers setup:

```kotlin
@Test
fun `persists and reads back an optional customer contact`() = runTest {
    val id = UUID.randomUUID()
    entityTemplate.insert(
        Order(id = id, status = OrderStatus.DRAFT, customerContact = "rider@example.com")
    ).awaitSingle()

    val loaded = orderRepository.findById(id)

    assertThat(loaded?.customerContact).isEqualTo("rider@example.com")
}

@Test
fun `customer contact is null when absent`() = runTest {
    val id = UUID.randomUUID()
    entityTemplate.insert(Order(id = id, status = OrderStatus.DRAFT)).awaitSingle()

    assertThat(orderRepository.findById(id)?.customerContact).isNull()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :orders-service:test --tests "*OrderPersistenceIT*"`
Expected: FAIL — `Order` has no `customerContact` parameter (compile error) or column missing.

- [ ] **Step 3: Add the migration**

`V5__add_customer_contact.sql`:
```sql
-- Optional per-order notification contact (email). NOT a customer identity:
-- nullable, carried to the order-confirmed event so Notifications can address
-- the real recipient. A NULL contact falls back to the demo default downstream.
ALTER TABLE orders
    ADD COLUMN customer_contact VARCHAR(320);
```

- [ ] **Step 4: Add the entity field** — `Order.kt`:

```kotlin
@Table("orders")
data class Order(
    @Id val id: UUID,
    val status: OrderStatus = OrderStatus.DRAFT,
    val customerContact: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```

- [ ] **Step 5: Add the request field** — in `OrderEvents.kt`, `CreateOrderRequest`:

```kotlin
/** Request DTO for POST /orders. `customerContact` is optional (best-effort notification). */
data class CreateOrderRequest(
    val routeId: UUID,
    val seats: Int,
    val customerContact: String? = null
)
```

- [ ] **Step 6: Persist the normalized contact** — in `OrderService.placeOrder`, replace the `entityTemplate.insert(Order(...))` call:

```kotlin
entityTemplate.insert(
    Order(
        id = orderId,
        status = OrderStatus.DRAFT,
        // Trim to null: blank is "not provided". No validation — a bad email
        // must never fail an order (best-effort notification).
        customerContact = request.customerContact?.trim()?.ifBlank { null }
    )
).awaitSingle()
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :orders-service:test --tests "*OrderPersistenceIT*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/orders-service/src/main/resources/db/migration/V5__add_customer_contact.sql \
        backend/orders-service/src/main/kotlin/com/eurotransit/orders/model/Order.kt \
        backend/orders-service/src/main/kotlin/com/eurotransit/orders/event/OrderEvents.kt \
        backend/orders-service/src/main/kotlin/com/eurotransit/orders/service/OrderService.kt \
        backend/orders-service/src/test/kotlin/com/eurotransit/orders/persistence/OrderPersistenceIT.kt
git commit -m "feat(orders): persist optional customer_contact on the order"
```

---

### Task 2: Orders — carry the contact into `order-confirmed` and return it on GET

**Files:**
- Modify: `backend/orders-service/src/main/kotlin/com/eurotransit/orders/event/OrderEvents.kt` (OrderConfirmedEvent, OrderResponse)
- Modify: `backend/orders-service/src/main/kotlin/com/eurotransit/orders/controller/OrderController.kt` (getOrder)
- Modify: `backend/orders-service/src/main/kotlin/com/eurotransit/orders/kafka/OrderKafkaConsumer.kt`
- Test: `backend/orders-service/src/test/kotlin/com/eurotransit/orders/kafka/OrderConfirmedContactTest.kt` (create)

**Interfaces:**
- Consumes: `Order.customerContact: String?` (Task 1); `OrderRepository.findById(id): Order?` (existing).
- Produces: `OrderConfirmedEvent(orderId: UUID, customerContact: String? = null, timestamp: Instant)`; `OrderResponse(orderId, status, message, customerContact: String? = null)`.

- [ ] **Step 1: Write the failing test** — `OrderConfirmedContactTest.kt`, a focused unit test of the event contract:

```kotlin
package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderConfirmedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OrderConfirmedContactTest {
    @Test
    fun `order-confirmed carries an optional customer contact`() {
        val id = UUID.randomUUID()
        val withContact = OrderConfirmedEvent(orderId = id, customerContact = "rider@example.com")
        assertThat(withContact.customerContact).isEqualTo("rider@example.com")

        // Backward compatible: contact is optional and defaults to null.
        val withoutContact = OrderConfirmedEvent(orderId = id)
        assertThat(withoutContact.customerContact).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :orders-service:test --tests "*OrderConfirmedContactTest*"`
Expected: FAIL — `OrderConfirmedEvent` has no `customerContact` parameter.

- [ ] **Step 3: Extend the events** — in `OrderEvents.kt`:

```kotlin
/** Published to topic `order-confirmed` after payment is authorized. */
data class OrderConfirmedEvent(
    val orderId: UUID,
    // Optional per-order recipient snapshot; Notifications falls back to its
    // default when null. MUST stay optional (a required field DLT'd real events).
    val customerContact: String? = null,
    val timestamp: Instant = Instant.now()
)
```

```kotlin
/** Response DTO returned from POST /orders and GET /orders/{id}. */
data class OrderResponse(
    val orderId: UUID,
    val status: String,
    val message: String,
    val customerContact: String? = null
)
```

- [ ] **Step 4: Return the contact on GET** — in `OrderController.getOrder`, include it in the OK response:

```kotlin
return ResponseEntity.ok(
    OrderResponse(
        orderId = order.id,
        status = order.status.name,
        message = "",
        customerContact = order.customerContact
    )
)
```

- [ ] **Step 5: Publish the contact on confirmation** — in `OrderKafkaConsumer.handle`, read the order's contact after the transition and include it. Replace the `if (confirmed == true) { ... }` block:

```kotlin
if (confirmed == true) {
    // Re-read the order to snapshot the recipient into the event (the
    // confirming update is count-based and does not return the row).
    val contact = orderRepository.findById(event.orderId)?.customerContact
    orderKafkaProducer.sendOrderConfirmed(
        OrderConfirmedEvent(orderId = event.orderId, customerContact = contact)
    )
    logger.info("Order {} confirmed after payment authorization", event.orderId)
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :orders-service:test --tests "*OrderConfirmedContactTest*" --tests "*OrderKafka*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/orders-service/src/main/kotlin/com/eurotransit/orders/event/OrderEvents.kt \
        backend/orders-service/src/main/kotlin/com/eurotransit/orders/controller/OrderController.kt \
        backend/orders-service/src/main/kotlin/com/eurotransit/orders/kafka/OrderKafkaConsumer.kt \
        backend/orders-service/src/test/kotlin/com/eurotransit/orders/kafka/OrderConfirmedContactTest.kt
git commit -m "feat(orders): carry customerContact into order-confirmed and GET response"
```

---

### Task 3: Notifications — real SMTP sender, selectable by property

**Files:**
- Modify: `backend/notifications-service/build.gradle.kts`
- Modify: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/LoggingEmailSender.kt`
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/SmtpEmailSender.kt`
- Modify: `backend/notifications-service/src/main/resources/application.yml` (or `.properties`)
- Test: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/email/SmtpEmailSenderTest.kt` (create)

**Interfaces:**
- Consumes: `EmailSender.send(event: OrderConfirmedEvent)` (existing interface); `OrderConfirmedEvent.customerContact: String` (notifications-side, already defaulted).
- Produces: `SmtpEmailSender` bean active only when `notifications.email.sender=smtp`; `LoggingEmailSender` active otherwise.

- [ ] **Step 1: Write the failing test** — `SmtpEmailSenderTest.kt`. Use GreenMail (in-memory SMTP) so we assert real send behavior without an external server:

```kotlin
package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl

class SmtpEmailSenderTest {
    private lateinit var greenMail: GreenMail

    @BeforeEach fun start() { greenMail = GreenMail(ServerSetupTest.SMTP).also { it.start() } }
    @AfterEach fun stop() { greenMail.stop() }

    private fun mailSender() = JavaMailSenderImpl().apply {
        host = "127.0.0.1"; port = greenMail.smtp.port
    }

    @Test
    fun `sends a confirmation to the event contact`() = runTest {
        val sender = SmtpEmailSender(mailSender(), SimpleMeterRegistry(), from = "noreply@eurotransit.test")

        sender.send(OrderConfirmedEvent(orderId = "order-1", customerContact = "rider@example.com"))

        val received = greenMail.receivedMessages
        assertThat(received).hasSize(1)
        assertThat(received[0].allRecipients[0].toString()).isEqualTo("rider@example.com")
    }
}
```

- [ ] **Step 2: Add dependencies** — `build.gradle.kts` `dependencies { }`:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-mail")
testImplementation("com.icegreen:greenmail:2.1.0")
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :notifications-service:test --tests "*SmtpEmailSenderTest*"`
Expected: FAIL — `SmtpEmailSender` does not exist.

- [ ] **Step 4: Implement `SmtpEmailSender`**:

```kotlin
package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * Real SMTP sender (demo: Mailtrap sandbox). Selected only when
 * `notifications.email.sender=smtp`; otherwise LoggingEmailSender runs.
 * A send failure/timeout throws — the caller treats a throw as a failure so the
 * Kafka error handler retries and eventually routes to the DLT (resilience path).
 */
@Component
@ConditionalOnProperty(name = ["notifications.email.sender"], havingValue = "smtp")
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    registry: MeterRegistry,
    @Value("\${notifications.email.from:noreply@eurotransit.test}") private val from: String,
) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sent = registry.counter("notifications_sent_total")

    override suspend fun send(event: OrderConfirmedEvent) {
        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(event.customerContact)
            setSubject("Your EuroTransit booking ${event.orderId} is confirmed")
            setText(
                "Your booking ${event.orderId} is confirmed. Thank you for riding EuroTransit."
            )
        }
        // JavaMailSender is blocking; keep it off the event-loop / consumer thread.
        withContext(Dispatchers.IO) { mailSender.send(message) }
        log.info("Sent order-confirmation for order={} to={}", event.orderId, event.customerContact)
        sent.increment()
    }
}
```

- [ ] **Step 5: Make the stub conditional** — annotate `LoggingEmailSender` so exactly one sender is active. In `LoggingEmailSender.kt` add the annotation and import:

```kotlin
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
// ...
@Component
@ConditionalOnProperty(name = ["notifications.email.sender"], havingValue = "logging", matchIfMissing = true)
class LoggingEmailSender(registry: MeterRegistry) : EmailSender {
```

- [ ] **Step 6: Add SMTP config keys** — in `application.yml`, add (JavaMail timeouts so a slow SMTP exercises retry→DLT rather than hanging):

```yaml
notifications:
  email:
    sender: ${NOTIFICATIONS_EMAIL_SENDER:logging}
    from: ${NOTIFICATIONS_EMAIL_FROM:noreply@eurotransit.test}
spring:
  mail:
    host: ${SPRING_MAIL_HOST:localhost}
    port: ${SPRING_MAIL_PORT:1025}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:
      mail.smtp.connectiontimeout: 3000
      mail.smtp.timeout: 3000
      mail.smtp.writetimeout: 3000
      mail.smtp.auth: ${SPRING_MAIL_AUTH:true}
      mail.smtp.starttls.enable: ${SPRING_MAIL_STARTTLS:true}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :notifications-service:test --tests "*SmtpEmailSenderTest*" --tests "*NotificationServiceTest*"`
Expected: PASS (existing `NotificationServiceTest` keeps using the stub / a mock `EmailSender`).

- [ ] **Step 8: Commit**

```bash
git add backend/notifications-service/build.gradle.kts \
        backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/ \
        backend/notifications-service/src/main/resources/application.yml \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/email/SmtpEmailSenderTest.kt
git commit -m "feat(notifications): add SmtpEmailSender selectable via notifications.email.sender"
```

---

### Task 4: Frontend — email input at checkout and recipient on the confirmed page

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/features/checkout/CheckoutPanel.tsx`
- Modify: `frontend/src/pages/OrderPage.tsx`
- Test: `frontend/src/features/checkout/CheckoutPanel.test.tsx` (create or extend existing checkout tests)

**Interfaces:**
- Consumes: `placeOrder(request, options)` where `request: CreateOrderRequest` (Task 2 shape mirrored).
- Produces: UI collects an optional email and sends `customerContact`; OrderPage shows `order.customerContact` on CONFIRMED.

- [ ] **Step 1: Write the failing test** — `CheckoutPanel.test.tsx` (Vitest + Testing Library, matching the repo's frontend test setup):

```tsx
import { describe, expect, it } from 'vitest'
import { isEmailValid } from './payment'

describe('optional checkout email', () => {
  it('treats empty as valid (optional)', () => {
    expect(isEmailValid('')).toBe(true)
  })
  it('accepts a well-formed address', () => {
    expect(isEmailValid('rider@example.com')).toBe(true)
  })
  it('rejects a malformed address when provided', () => {
    expect(isEmailValid('nope@')).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- CheckoutPanel`
Expected: FAIL — `isEmailValid` is not exported from `./payment`.

- [ ] **Step 3: Add the validator** — in `frontend/src/features/checkout/payment.ts`:

```ts
// Lenient, optional: empty is valid (email is not required). Only a non-empty,
// clearly malformed address is rejected — a bad email must never block checkout.
export function isEmailValid(value: string): boolean {
  const v = value.trim()
  if (v === '') return true
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)
}
```

- [ ] **Step 4: Extend the DTO** — in `frontend/src/api/types.ts`:

```ts
export interface CreateOrderRequest {
  routeId: string
  seats: number
  customerContact?: string
}

export interface OrderResponse {
  orderId: string
  status: OrderStatus
  /** "Order accepted for processing" on POST; empty string on GET. */
  message: string
  /** Optional per-order notification recipient (echoed back on GET). */
  customerContact?: string
}
```

- [ ] **Step 5: Add the input + send the value** — in `CheckoutPanel.tsx`:
  1. Add state near the card state: `const [email, setEmail] = useState('')`.
  2. In `pay()`, block only on a *malformed* email: `if (!isEmailValid(email)) { setShowCardErrors(true); return }` (import `isEmailValid` from `./payment`).
  3. In `book()`, pass the trimmed contact:

```tsx
const { order } = await placeOrder(
  {
    routeId: route.id,
    seats,
    customerContact: email.trim() || undefined,
  },
  { idempotencyKey, onRetry: (attempt) => setPhase({ kind: 'submitting', retryAttempt: attempt }) },
)
```

  4. Add the field in the payment `<form>` (after the card fields grid), reusing the existing `CardField` component:

```tsx
<CardField
  label="Email (optional)"
  value={email}
  onChange={setEmail}
  error={showCardErrors && !isEmailValid(email) ? 'Enter a valid email, or leave it blank.' : ''}
  autoComplete="email"
  inputMode="email"
  placeholder="you@example.com"
  disabled={submitting}
/>
```

- [ ] **Step 6: Show the recipient on confirmation** — in `OrderPage.tsx`, the existing CONFIRMED block (currently "your confirmation is on its way"), make the recipient explicit when present:

```tsx
{order.status === 'CONFIRMED' && (
  <div className="mt-4 flex items-start gap-3 rounded-lg bg-go-soft p-4 text-sm text-go">
    <PartyPopper className="mt-0.5 size-5 shrink-0" aria-hidden />
    <p>
      Your booking is confirmed and your confirmation is on its way
      {order.customerContact ? <> to <strong>{order.customerContact}</strong></> : null}. Find this
      trip anytime under{' '}
      <Link to="/orders" className="font-semibold underline">My trips</Link>.
    </p>
  </div>
)}
```

- [ ] **Step 7: Run tests + typecheck to verify they pass**

Run: `cd frontend && npm test -- CheckoutPanel && npm run build`
Expected: tests PASS; build/tsc succeeds (OrderResponse now carries `customerContact`).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/checkout/ frontend/src/pages/OrderPage.tsx
git commit -m "feat(frontend): optional checkout email + show confirmation recipient"
```

---

### Task 5: Config repo — Mailtrap SealedSecret + notifications env (companion PR)

**Repo:** `eurotransit-config` (separate PR; requires the cluster's kubeseal cert). Follow the repo's PR flow — no direct cluster apply.

**Files:**
- Create: `deploy/charts/eurotransit/templates/notifications-smtp-sealedsecret.yaml` (or the repo's SealedSecret location — match existing `sealed-secrets` usage)
- Modify: `deploy/charts/eurotransit/values.yaml` (notifications env block)
- Modify: notifications Deployment template to inject the env (match the existing per-service pattern)

**Interfaces:**
- Consumes: env keys read by Task 3 config — `NOTIFICATIONS_EMAIL_SENDER`, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`.

- [ ] **Step 1: Create the plaintext Secret locally (never committed)**

```bash
kubectl create secret generic notifications-smtp \
  --namespace eurotransit \
  --from-literal=SPRING_MAIL_USERNAME='<mailtrap-user>' \
  --from-literal=SPRING_MAIL_PASSWORD='<mailtrap-pass>' \
  --dry-run=client -o yaml > /tmp/notifications-smtp.yaml
```

- [ ] **Step 2: Seal it with the cluster cert** (produces the committable SealedSecret):

```bash
kubeseal --format yaml < /tmp/notifications-smtp.yaml \
  > deploy/charts/eurotransit/templates/notifications-smtp-sealedsecret.yaml
rm /tmp/notifications-smtp.yaml
```

- [ ] **Step 3: Wire notifications env** — in `values.yaml`, add non-secret values under the notifications service and reference the SealedSecret for credentials:

```yaml
notifications:
  env:
    NOTIFICATIONS_EMAIL_SENDER: "smtp"
    NOTIFICATIONS_EMAIL_FROM: "noreply@eurotransit.test"
    SPRING_MAIL_HOST: "sandbox.smtp.mailtrap.io"
    SPRING_MAIL_PORT: "2525"
  envFromSecret: notifications-smtp   # SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD
```

  Then in the notifications Deployment template, render `env:` from `.env` and `envFrom: [secretRef: notifications-smtp]` — following the exact pattern the chart already uses for other services (do not invent a new mechanism).

- [ ] **Step 4: Offline gate + PR**

```bash
just helm-verify
just helm-schema
git add deploy/charts/eurotransit/templates/notifications-smtp-sealedsecret.yaml deploy/charts/eurotransit/values.yaml deploy/charts/eurotransit/templates/
git commit -m "feat(notifications): SMTP (Mailtrap) env + sealed credentials"
# open PR → approval → merge → Argo CD reconciles
```

---

### Task 6: Record the AI-assisted contract change in the agent log

**Files:**
- Modify: `docs/agent-log.md`

- [ ] **Step 1: Append an entry** describing: the AI proposed adding an optional `customerContact` to the `order-confirmed` event and threading email end-to-end; the key correctness decision (field kept optional/nullable to avoid the prior DLT regression; email never fails an order); reviewed and merged through the PR gate. Include the date (2026-07-16) and the spec/plan paths.

- [ ] **Step 2: Commit**

```bash
git add docs/agent-log.md
git commit -m "docs(agent-log): record customerContact / SMTP notification change"
```

---

## Self-Review

**Spec coverage:**
- Frontend email input + honest recipient copy → Task 4 ✓
- `CreateOrderRequest.customerContact`, `OrderResponse.customerContact` → Tasks 1, 2, 4 ✓
- Persist `customer_contact` (migration + entity + service) → Task 1 ✓
- `OrderConfirmedEvent` carries contact from the async saga → Task 2 ✓
- `SmtpEmailSender` + `@ConditionalOnProperty` + timeouts + stub default → Task 3 ✓
- Config repo SealedSecret + env → Task 5 ✓
- agent-log entry → Task 6 ✓

**Placeholder scan:** No TBD/TODO; every code step shows code. Task 5 Step 3 references "the exact pattern the chart already uses" — intentional (must match existing chart mechanism, verified at implementation), not a placeholder for logic.

**Type consistency:** `customerContact: String?` (Kotlin) / `customerContact?: string` (TS) / column `customer_contact` used consistently; `OrderConfirmedEvent(orderId, customerContact, timestamp)` matches producer (Task 2) and the existing notifications-side defaulted field; `isEmailValid` defined in Task 4 Step 3 and used in Steps 1/5.
