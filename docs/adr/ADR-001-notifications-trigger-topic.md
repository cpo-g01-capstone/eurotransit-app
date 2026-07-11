# ADR-001 — Notifications consumes `order-confirmed` directly (not `notification-requested`)

**Date:** 2026-07-08
**Status:** Accepted
**Owner:** @marcodonatucci (proposed on behalf of the team; pending team ratification per AI usage policy)

## Context

The Notifications service is the terminal, fully-asynchronous consumer on the money
path: Orders confirms the order in its PostgreSQL DB and publishes `order-confirmed`
(money-path step 6) **before** Notifications is ever involved (step 7). Notifications
must be able to fail entirely without failing checkout (graceful degradation).

The context docs are inconsistent about **which topic drives Notifications**:

- `.agent/context/money-path.md` (step 7): Notifications consumes **`order-confirmed`** only.
- `.agent/context/kafka-topics.md`: lists Notifications as consumer of **both**
  `order-confirmed` **and** `notification-requested`.

We must pick one contract before wiring the `@KafkaListener`. Two shapes were considered:

- **A — consume `order-confirmed`** (the domain event): Notifications interprets the
  authoritative domain fact and builds the confirmation itself.
- **B — consume `notification-requested`** (a command): Orders emits a second, explicit
  "send this notification" event after confirming; Notifications is a dumb executor.

The team's cross-cutting preference is **consistency over availability**.

## Decision

Notifications subscribes to **`order-confirmed` only** (option A). The
`notification-requested` topic is **not** consumed and is treated as unused (see
Consequences / agent-log Case 4 for the required cleanup).

## Rationale

- **Single source of truth.** With A there is exactly one authoritative fact
  (`order-confirmed`). "The order is confirmed" and "it must be notified" are the same
  event, so there is no window in which notification state can diverge from order state.
  The invariant becomes strong and simple: *notify iff `order-confirmed` was committed.*
- **No dual-write / no outbox.** Option B requires Orders to publish **two** events after
  confirming (`order-confirmed` **and** `notification-requested`). A crash between the two
  yields a confirmed order with no notification requested — a consistency gap that could
  only be closed with a transactional outbox on Orders. Given the team's
  consistency-over-availability preference, avoiding this dual-write is the coherent choice.
- **Zero impact on Orders.** `order-confirmed` already exists in the money path; A needs no
  new producer code on a service this task does not own. B would add work and complexity to
  Orders.
- **Contact/recipient data** can travel inside the `order-confirmed` payload, so B's
  supposed advantage (Orders pre-building the notification) is not required.

Trade-off accepted: A couples Notifications to the `order-confirmed` schema and offers less
decoupling/extensibility. Adding future notification types/channels (SMS, push) will mean
new consumers on domain events or revisiting this decision — an acceptable cost, since B's
main benefit is availability/flexibility of the notification subsystem, which is exactly
what we are de-prioritising.

## Consequences

**Easier:**
- Strong, defensible invariant (`notify iff order-confirmed committed`); no outbox needed.
- No change required to Orders.
- Idempotency key is unambiguous: `orderId` + event type `order-confirmed`. The concrete
  deduplication store is decided in **ADR-002** (dedicated PostgreSQL).

**Harder / follow-ups:**
- Notifications is coupled to the `order-confirmed` event schema.
- Future notification types need a new design (new consumer or superseding ADR).
- **`notification-requested` is now an orphan topic** (declared but never produced/consumed).
  It must be removed from `.agent/context/kafka-topics.md` and any `KafkaTopic` CRs, or
  explicitly annotated as "reserved, not yet wired". Tracked as **agent-log Case 4**.

## References

- `.agent/context/money-path.md`, `.agent/context/kafka-topics.md` (config repo)
- Capstone PDF: "Notifications — Sends confirmations. Fully asynchronous; failure must
  degrade gracefully."
- agent-log Case 4 (config repo `docs/agent-log.md`) — the topology inconsistency this ADR resolves.
