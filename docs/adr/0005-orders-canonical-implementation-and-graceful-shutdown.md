# ADR 0005 — Canonical Orders implementation (D3) and the graceful-shutdown pattern

- **Status:** Proposed
- **Date:** 2026-07-11
- **Deciders:** implementation decisions by @Lollegro (EM-25/EM-21 convergence);
  recorded, reviewed and annotated by @giova95; team to ratify (decision D3)
- **Context tags:** orders, kafka, graceful-shutdown, idempotency, resilience
- **Supersedes / Superseded by:** —
- **Related:** decision D3 (Orders convergence); config-repo ADR 0018 (synchronous payment
  authorization + circuit breaker, decision D1); `docs/design/idempotency.md`,
  `docs/design/service-boundaries.md` (config repo); ADR 0001..0004 (notifications).

## Context

Three divergent Orders implementations existed: EM-19 (pipeline without Kafka dedup,
minimal events), EM-21 (coroutine lifecycle + saga, merged to `main` as
`api/ config/ domain/ events/ pipeline/`), EM-25 (HTTP + Kafka idempotency). Decision
**D3** picked EM-25 as the canonical base; EM-19 was deleted. The updated EM-25 branch
(`feature/EM-25-idempotency-payments-orders`, commit `04fa8fd`) now **re-implements the
EM-21 goals on top of the idempotent base**: this ADR records how, and what must happen
at merge time.

## Decision — the converged implementation (EM-25 branch)

One implementation per concern, across orders/inventory/payments:

1. **Idempotent consumers** (Pillar B): read-before-write check on `processed_events`,
   business write + dedup insert in ONE transaction, downstream publish outside the
   transaction. At-least-once + dedup — a redelivered event is a no-op.
2. **Complete event contract**: `OrderPlacedEvent(orderId, routeId, seats, timestamp,
idempotencyKey)` — fixes EM-19's orderId-only event that starved Inventory of data.
3. **Graceful shutdown** (`lifecycle/GracefulShutdownManager`, ~60 lines copied per
   service — deliberate, see §Trade-offs):
   - `SmartLifecycle.stop(callback)` (async): flip readiness via
     `AvailabilityChangeEvent.publish(REFUSING_TRAFFIC)` → Actuator readiness returns 503
     → K8s removes the pod from endpoints; then poll an `AtomicInteger` in-flight counter
     (500 ms) with a **45 s budget**, leaving 5 s margin inside Spring's 50 s phase
     timeout; chain invariant `preStop(5s) + Spring(50s) < terminationGracePeriod(60s)`
     → no SIGKILL during an orderly drain.
   - Consumers guard with `isAcceptingTraffic()`: during drain, new messages get an
     **early return WITHOUT ack** → offset uncommitted → rebalanced to a healthy
     instance. Not lost (at-least-once kept), not double-processed (dedup).
   - `coroutineContext.ensureActive()` **between the DB transaction and the downstream
     publish**: the one point where cooperative cancellation is both safe (data already
     committed) and useful (publish will be redone idempotently on redelivery).
     Inside the TX it could cancel between UPDATE and COMMIT; after the publish it is useless.
4. **Catalog / Notifications**: only `server.shutdown: graceful` — no manager. Catalog is
   stateless read-only (WebFlux drains HTTP natively); Notifications already has its own
   dedup/DLT design (ADR 0001..0004) and redelivery-safe consumers.

### Why this is the right base (theory + assignment)

- _"Readiness flips to refusing traffic while in-flight work drains"_ — implemented with
  Spring's **native** availability mechanism, not a custom flag.
- _"No orphaned tasks, no double-processing"_ — early-return-no-ack preserves
  at-least-once; the dedup layer converts redelivery into no-ops. The two halves are
  designed **together**, which is exactly the A↔B pillar coupling the course teaches.
- _"Configured deliberately rather than defaulted"_ — the 5/45/50/60 timeout chain is an
  explicit, verifiable invariant (mirrors config-repo ADR 0002).
- Bounded drain + explicit budget beats EM-21's `withContext(NonCancellable)` blocks:
  correctness comes from at-least-once + dedup, not from forbidding cancellation.

## ⚠️ Corrections and pre-merge conditions (review findings)

1. **`getPhase() = Int.MAX_VALUE` — the comment/notes have the semantics inverted.**
   The code comment says the bean "stops LAST, after Kafka containers". Spring stops
   `SmartLifecycle` beans in **descending** phase order: highest phase starts last and
   **stops FIRST**. With `Int.MAX_VALUE` the manager stops _before_ the Kafka listener
   containers (default phase `Int.MAX_VALUE - 100`) — and that is precisely what the
   design needs: flip readiness + start draining while containers still poll, with the
   `isAcceptingTraffic()` guard rejecting new work. **The behaviour is right; the
   explanation is wrong.** Fix the comment (and the local notes) — an examiner probing
   "what does this phase do?" must get the correct answer.
2. **Bug — `order-confirmed` published even when the order was NOT confirmed.**
   In `OrderKafkaConsumer.handlePaymentAuthorized`, when `updateStatus` returns `0`
   (order not in `RESERVED`), the code logs, inserts the dedup record… and then still
   publishes `OrderConfirmedEvent` unconditionally. Notifications would send a
   confirmation for an order that was never confirmed. **Fix before merge:** publish only
   when `updated == 1` (keep the dedup insert in both cases).
3. **Merge plan — the EM-21 legacy files on `main` MUST be deleted in the merge commit.**
   The branch adds parallel classes in different packages; git will merge with **zero
   conflicts** and a broken app: two `@RestController @RequestMapping("/orders")` (Spring
   fails startup on ambiguous mapping), two consumers on `payment-authorized`, two `Order`
   entities on the same table. Delete on merge:
   - `backend/orders-service/.../api/OrderController.kt`, `api/dto/OrderDtos.kt`
   - `backend/orders-service/.../config/CoroutineConfig.kt`
   - `backend/orders-service/.../domain/Order.kt`, `domain/OrderRepository.kt`
   - `backend/orders-service/.../events/KafkaEventPublisher.kt`
   - `backend/orders-service/.../pipeline/OrderPipelineCoordinator.kt`
   - review `scripts/Test-EM21-GracefulShutdown.ps1` (targets the old implementation).
4. **Repo hygiene:** the branch commits Gradle `build/` outputs (hundreds of binary
   files). Add `**/build/` to `.gitignore` and purge them — binary churn hides real diffs
   in review.

## Consequences / next steps

- After merge, the **ADR 0018 work** (synchronous `Orders → Payments` authorize with the
  Resilience4j breaker, decision D1) is implemented **on this base** — it replaces the
  Kafka hop `inventory-reserved → Payments` decision point with the sync call, and
  unblocks chaos experiment CE-1.
- The trade-off "copy per service, no shared Gradle module" is accepted (≈60 lines,
  existing convention with `ProcessedEvent*`, zero build coupling, teams can diverge
  timeouts deliberately). Revisit only if a third copy-drift bug appears.
- The shutdown claims must be **demonstrated, not asserted** (assignment): kill a pod
  mid-flow (CE-2) and show no orphaned work and no double-processing on the dashboards.
