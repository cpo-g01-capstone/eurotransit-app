# ADR 0001 — Notifications consumes `order-confirmed` directly (not `notification-requested`)

- **Status:** Accepted
- **Date:** 2026-07-08
- **Deciders:** _@marcodonatucci (drafted on behalf of the team, notifications); full team to ratify per AI-usage policy_
- **Context tags:** kafka, notifications, money-path, topology, consistency
- **Supersedes / Superseded by:** —

---

## Context

The Notifications service is the terminal, fully-asynchronous consumer on the money
path: Orders confirms the order in its PostgreSQL DB and publishes `order-confirmed`
(money-path step 6) **before** Notifications is ever involved (step 7). Notifications
must be able to fail entirely without failing checkout (graceful degradation).

The context docs are inconsistent about **which topic drives Notifications**:

- `.agent/context/money-path.md` (step 7): Notifications consumes **`order-confirmed`** only.
- `.agent/context/kafka-topics.md`: lists Notifications as consumer of **both**
  `order-confirmed` **and** `notification-requested`.

We must pick one contract before wiring the `@KafkaListener`. The team's cross-cutting
preference is **consistency over availability**.

## Decision

Notifications subscribes to **`order-confirmed` only**. It interprets the authoritative
domain fact and builds the confirmation itself. The `notification-requested` topic is
**not** consumed and is treated as unused (see Consequences / agent-log Case 4 for the
required cleanup).

The decisive properties:

- **Single source of truth.** There is exactly one authoritative fact (`order-confirmed`).
  "The order is confirmed" and "it must be notified" are the same event, so notification
  state can never diverge from order state. The invariant is strong and simple:
  *notify iff `order-confirmed` was committed.*
- **No dual-write / no outbox.** Orders does not have to emit a second event, so there is
  no crash window between two writes and no need for a transactional outbox on Orders.
- **Zero impact on Orders.** `order-confirmed` already exists on the money path; this needs
  no new producer code on a service this task does not own.
- **Recipient data** travels inside the `order-confirmed` payload.

## Alternatives considered

- **B — consume `notification-requested`** (a command): Orders emits a second, explicit
  "send this notification" event after confirming; Notifications is a dumb executor.
  **Rejected:** requires Orders to publish **two** events after confirming; a crash between
  them yields a confirmed order with no notification requested — a consistency gap that
  could only be closed with a transactional outbox on Orders. It also adds work and
  complexity to a service this task does not own. Its main benefit — availability/flexibility
  of the notification subsystem — is exactly what the team is de-prioritising.

Trade-off accepted for the chosen option: it couples Notifications to the `order-confirmed`
schema and offers less decoupling/extensibility. Adding future notification types/channels
(SMS, push) will mean new consumers on domain events or a superseding ADR.

## Consequences

**Easier**
- Strong, defensible invariant (`notify iff order-confirmed committed`); no outbox needed.
- No change required to Orders.
- Idempotency key is unambiguous: `orderId` + event type `order-confirmed`. The concrete
  deduplication store is decided in **ADR 0002** (dedicated PostgreSQL).

**Harder / follow-ups**
- Notifications is coupled to the `order-confirmed` event schema.
- Future notification types need a new design (new consumer or superseding ADR).
- **`notification-requested` is now an orphan topic** (declared but never produced/consumed).
  It must be removed from `.agent/context/kafka-topics.md` and any `KafkaTopic` CRs, or
  explicitly annotated as "reserved, not yet wired". Tracked as **agent-log Case 4**.

## Verification & ownership (agentic-coding policy)

This decision was drafted with agent assistance and **must be verified by the team** before
ratification:

- [ ] Ratify the consistency-over-availability trade-off (coupling to the `order-confirmed`
      schema, reduced extensibility).
- [ ] Confirm the `order-confirmed` payload carries the recipient/contact snapshot needed to
      build the confirmation without a second event.
- [ ] Remove or annotate the orphan `notification-requested` topic in config-repo
      `.agent/context/kafka-topics.md` and any `KafkaTopic` CRs (agent-log Case 4).

## References

- `.agent/context/money-path.md`, `.agent/context/kafka-topics.md` (config repo)
- Capstone PDF: "Notifications — Sends confirmations. Fully asynchronous; failure must
  degrade gracefully."
- agent-log Case 4 (config repo `docs/agent-log.md`) — the topology inconsistency this ADR resolves.
- ADR 0002 — deduplication store.
