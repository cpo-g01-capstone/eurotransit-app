# Notifications consumer — design spec

**Date:** 2026-07-08
**Status:** Approved (design); implementation pending
**Scope:** `eurotransit-app` — `backend/notifications-service` (source, build, tests).
Deployment artifacts (CloudNativePG cluster, KafkaTopic CRs, Helm probes/PDB/ServiceMonitor,
SealedSecrets) are **out of scope for this repo** and tracked as config-repo follow-ups.

This spec consolidates ADR-001…004 (`docs/adr/`). Those ADRs are the authoritative record of
each architectural decision and its rationale; this document is the implementation-facing view.

---

## 1. Goal & context

The Notifications service sends order-confirmation notifications by consuming the
`order-confirmed` domain event from Kafka. It is the **terminal, fully-asynchronous** stage of
the money path and is **off the critical path**: Orders confirms the order in its DB and
publishes `order-confirmed` (step 6) before Notifications is involved (step 7).

**Hard requirement (PDF + `CLAUDE.md`):** *Notifications may fail entirely without failing
checkout (graceful degradation).* Because Orders reaches Notifications only through Kafka (never
a synchronous call), this property is architecturally inherent — this design must **preserve**
it, never introduce a synchronous coupling back to checkout.

**Cross-cutting preference:** consistency over availability.

---

## 2. Architectural decisions (summary)

| ADR | Decision |
|-----|----------|
| 001 | Trigger topic = `order-confirmed` only. `notification-requested` is not consumed (orphan topic → config-repo cleanup, agent-log Case 11). |
| 002 | Deduplication in a **dedicated PostgreSQL** DB (CloudNativePG `eurotransit-notifications-db`), table `sent_notifications`. |
| 003 | At-least-once; **manual** offset ack; two-phase `pending → sent` row; send failure → bounded retry → `order-confirmed.DLT`; Notifications-DB down → **block-and-lag**. |
| 004 | Liveness = local process only; readiness = lifecycle only (drain), **not** Kafka/DB. |

---

## 3. Components (`com.eurotransit.notifications`)

Each unit has one purpose and a clear interface, so it can be tested independently.

| Unit | Responsibility | Depends on |
|------|----------------|-----------|
| `config/KafkaConfig` | Consumer factory (JSON deser, `AckMode.MANUAL`), `DefaultErrorHandler` (backoff+jitter) + `DeadLetterPublishingRecoverer` → `order-confirmed.DLT`, DLT producer factory | spring-kafka |
| `listener/OrderConfirmedListener` | `@KafkaListener` on `order-confirmed`; delegates to `NotificationService` | `NotificationService` |
| `service/NotificationService` | Orchestrates: dedup-claim → send → mark-sent; idempotent | `DedupRepository`, `EmailSender` |
| `persistence/DedupRepository` | Conditional insert / status transitions on `sent_notifications` (R2DBC) | R2DBC PostgreSQL |
| `email/EmailSender` (stub) | Logs the notification + increments Micrometer counters | Micrometer |
| `lifecycle/LifecycleConfig` | Service `CoroutineScope(SupervisorJob()+Dispatchers.IO)`; SIGTERM → stop listener, drain, cancel | — |

**Event schema (`OrderConfirmedEvent`):** `orderId`, `customerContact` (email/recipient snapshot
carried in the payload — no extra call back to Orders), `confirmedAt`, plus fields needed for the
message body. Exact schema to be aligned with the Orders producer contract.

**Implementation note — listener bridge (differs from the original plan; needs team ratification):**
The handler is **not** a `suspend` @KafkaListener. In this Spring Kafka version a `suspend`
listener does not propagate exceptions to the `DefaultErrorHandler` (retries/DLT never fire — see
agent-log Case 12), and a typed payload param on a non-suspend method deserialized to `KafkaNull`.
The working form takes the **raw `ConsumerRecord`** and bridges to the suspending service with
**`runBlocking`**, so exceptions surface synchronously and reach the error handler. `runBlocking`
here contradicts the `CLAUDE.md` "no runBlocking outside bootstrap" rule; the Kafka consumer thread
is a dedicated blocking poll loop (not a reactive context), so it is arguably the correct place,
but **the team must ratify this exception or pick another bridge**, and update ADR-004 accordingly.
The service `CoroutineScope` (`NotificationsLifecycle`) is still the failure domain used by the
error-handler recoverer's fire-and-forget mark-`FAILED`.

---

## 4. Data flow

**Idempotency key:** `orderId` + event type `order-confirmed` (composite; per `CLAUDE.md`).

**Happy path**
1. Consume `order-confirmed`.
2. `DedupRepository` conditional insert `status='pending'`.
   - Row already `sent` → **dedup hit**, skip, ack.
   - Row already `pending` (prior incomplete attempt) → proceed to retry the send.
3. `EmailSender.send()` (stub: log + `notifications_sent_total`).
4. Update row `status='sent'`.
5. **Manual ack** (offset advances only now).

**Failure paths**
- **Redelivery / rebalance / pod kill:** step 2 short-circuits on `sent`; `pending` rows are
  retried. Guarantee: *at most one send per confirmed order* in every case **except** the
  narrow window where a crash occurs after the external send but before the row is marked
  `sent` — then the retry re-sends. This residual duplicate is inherent to a non-transactional
  external side effect (you cannot atomically commit "email sent" with the send itself) and is
  **harmless for the log-based stub**. A real sender would need a provider-side idempotency key
  to close it. This is the deliberate at-least-once trade-off recorded in ADR-003.
- **Send failure:** bounded retry (backoff + jitter). On exhaustion → publish to
  `order-confirmed.DLT`, mark row `failed`, ack. **Main partition keeps flowing** (no
  head-of-line blocking).
- **Notifications DB down:** do **not** ack; container retries with backoff; events accumulate
  as consumer lag; caught up on DB recovery. No loss, no wrong notification.
- **Checkout:** never affected in any path — dedup DB and stub are downstream of
  `order-confirmed`.

---

## 5. Lifecycle & probes (ADR-004)

- Liveness `/actuator/health/liveness` — local process only; never checks Kafka/DB.
- Readiness `/actuator/health/readiness` — lifecycle only; UP when started, REFUSING during
  drain. **Do not** add `db`/`kafka` indicators to the readiness group (Spring's default group
  is already correct — the trap is "helpfully" adding them).
- Structured concurrency: one `CoroutineScope(SupervisorJob()+Dispatchers.IO)`; SIGTERM →
  stop the listener container → drain in-flight handlers → cancel scope → exit. No
  `GlobalScope`; no `runBlocking` outside bootstrap.

---

## 6. Build & schema changes (this repo)

- `backend/notifications-service/build.gradle.kts`: add R2DBC PostgreSQL, Flyway, PostgreSQL
  JDBC driver (Flyway runs on JDBC; R2DBC is runtime), mirroring `orders-service`.
- `src/main/resources/application.yml`: R2DBC URL + Flyway JDBC URL/creds (env-injected from the
  operator secret `eurotransit-notifications-db-app`), Kafka bootstrap
  (`eurotransit-kafka-kafka-bootstrap.eurotransit:9092`), consumer group, manual-ack container
  factory settings. Probes already enabled.
- `src/main/resources/db/migration/V1__init_notifications_schema.sql`: `sent_notifications`
  (`order_id` PK, `status` ∈ {`pending`,`sent`,`failed`}, timestamps).

---

## 7. Observability

- Micrometer counters: `notifications_sent_total`, `notifications_failed_total`; DLT-published
  counter.
- Consumer-lag and DLT-depth metrics feed **symptom-based** alerts (thresholds team-owned,
  config-repo `slo-definitions.md`).

---

## 8. Testing strategy

- **Unit — dedup:** redelivery of the same `orderId` does not re-send.
- **Unit — two-phase:** crash simulated between `pending` and `sent` → next delivery retries.
- **Integration — listener + DLT:** `@EmbeddedKafka` (or Testcontainers) + Testcontainers
  PostgreSQL; assert happy-path send, and that an always-failing send lands in
  `order-confirmed.DLT` after bounded retries without blocking a following good message.
- **Integration — DB down:** dedup DB unavailable → offset not committed → message reprocessed
  on recovery (no loss, no duplicate).
- **Lifecycle:** SIGTERM drains in-flight handler, readiness refuses during drain, no
  double-processing.

---

## 9. Config-repo follow-ups (twin PR; not owned here)

1. CloudNativePG `Cluster` `eurotransit-notifications-db` (+ `-rw`/`-ro` services) and its
   SealedSecret.
2. `KafkaTopic` CR `order-confirmed.DLT` (partitions/retention team-owned); add to
   `.agent/context/kafka-topics.md`.
3. Remove/annotate orphan topic `notification-requested` (agent-log Case 11).
4. Helm: notifications probes (liveness/readiness paths above), `resources:`, PDB decision,
   ServiceMonitor.
5. `docs/design/idempotency.md`: fill the `order-confirmed` row (owner @MauroC0l).

---

## 10. Team-owned knobs (AI must not decide — AI policy)

Retry attempts / backoff / jitter; DLT partitions & retention; consumer-lag & DLT-depth alert
thresholds; DLT replay/discard runbook; PDB `minAvailable`; the `order-confirmed` payload
contract with Orders. This spec fixes only the **scheme**; these values are team decisions.
