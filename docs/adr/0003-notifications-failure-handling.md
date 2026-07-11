# ADR 0003 — Notifications failure handling & offset-commit semantics (at-least-once + DLT)

- **Status:** Accepted
- **Date:** 2026-07-08
- **Deciders:** _@marcodonatucci (drafted on behalf of the team, notifications); full team to ratify per AI-usage policy_
- **Context tags:** kafka, notifications, idempotency, dlt, resilience, consistency
- **Supersedes / Superseded by:** —

---

## Context

Notifications consumes `order-confirmed` (ADR 0001), deduplicates in a dedicated PostgreSQL
store (ADR 0002), and "sends" via a log-based stub. Three questions were left open by
ADR 0002, all raised by the combination of at-least-once delivery, a **non-transactional**
external send, and a stateful dedup store:

1. When are Kafka offsets committed?
2. What happens when a send fails (including a poison message)?
3. What happens when Notifications' **own** database is down?

Plus the ordering of "record dedup row" vs "send email". The team's preference is
**consistency over availability**, and Notifications is best-effort and **off the critical
path** (checkout has already completed at `order-confirmed`).

## Decision

Bundle 1 — consistency-first, at-least-once, dead-letter:

- **Manual offset ack.** Commit the offset **only after** the handler has durably recorded
  handling and attempted the send (`AckMode.MANUAL`/`RECORD`). Result: at-least-once, no
  lost events.
- **Two-phase dedup row.** Conditional insert `status='pending'` → send → update
  `status='sent'`. On redelivery: `sent` = dedup hit (skip); `pending` = a prior attempt did
  not complete → retry the send. This resolves ADR 0002's insert-vs-send ordering and adds a
  `status` column (`pending | sent | failed`) to the `sent_notifications` migration.
- **Send failure → bounded retry → DLT.** Retry with backoff + jitter; on exhaustion,
  publish the record to a dead-letter topic **`order-confirmed.DLT`** and mark the row
  `failed`. The main partition keeps flowing — **no head-of-line blocking**.
- **Notifications DB down → block-and-lag.** Do **not** commit the offset; the container
  retries with backoff. Events accumulate as consumer lag and are caught up when the DB
  recovers. No loss, no wrong email. Because Notifications is off the critical path, growing
  lag never affects checkout.
- **Checkout invariant preserved.** The dedup DB and the stub sender are downstream of
  `order-confirmed`; their failure cannot fail checkout (ADR 0001 / ADR 0002).

Why this bundle:

- At-least-once + durable dedup + two-phase status gives *"exactly one confirmation email
  per confirmed order"* as an effective guarantee under redelivery, rebalance, and pod kill —
  the consistency property the team is optimising for.
- Bounded-retry-then-DLT avoids the failure mode of infinite retry (one poison message
  stalling **all** later notifications). Best-effort notifications tolerate parking a bad
  record in the DLT for out-of-band handling.
- Block-and-lag on DB outage prefers correctness (no dropped/duplicate notification) over
  availability of the notification itself — acceptable precisely because Notifications is not
  latency-critical and cannot affect checkout.

## Alternatives considered

- **Auto-commit / commit-before-send** — rejected: an offset committed before the durable
  record and send would drop events on crash, breaking at-least-once.
- **Infinite retry on send failure (no DLT)** — rejected: a single poison message would stall
  **all** later notifications (head-of-line blocking).
- **Skip / drop on DB outage** — rejected: it produces dropped or wrong notifications, the
  opposite of the team's consistency preference. Block-and-lag is chosen instead, and it is
  safe because Notifications is off the critical path.

### Reserved to the team (not decided here)

Per the AI-usage policy, this ADR fixes only the **scheme**. The following **numeric/policy
knobs** are resilience-owner/team decisions:

- retry attempts, backoff base/max, jitter;
- `order-confirmed.DLT` partitions and retention;
- alerting thresholds on consumer lag and DLT depth (symptom-based);
- the operational runbook for replaying/discarding DLT records.

## Consequences

**Easier**
- No lost or duplicate notifications under redelivery, rebalance, or pod kill.
- Poison messages isolated in the DLT instead of blocking the pipeline.
- Strong chaos story: kill the dedup DB or partition Kafka → lag grows → recovers, and
  checkout stays green (demonstrable, not merely claimed).

**Harder / follow-ups**
- **New `KafkaTopic` CR `order-confirmed.DLT`** (config repo): add to
  `.agent/context/kafka-topics.md` and declare the CR (partitions/retention TBD by team).
- spring-kafka wiring: manual `AckMode`, `DefaultErrorHandler` with backoff +
  `DeadLetterPublishingRecoverer` (or Spring Retry Topics), and a Kafka **producer** config
  for the DLT.
- `sent_notifications` Flyway migration gains the `status` column (ADR 0002).
- Observability: consumer-lag metric + DLT-depth metric; symptom-based alert (thresholds
  owned by the team). Feeds config-repo `docs/design/slo-definitions.md`.
- DLT operational runbook (manual replay / discard) — team-owned.

## Verification & ownership (agentic-coding policy)

This decision was drafted with agent assistance and **must be verified by the team** before
ratification:

- [ ] Set the reserved numeric/policy knobs (retry attempts, backoff/jitter, DLT
      partitions/retention, lag & DLT-depth alert thresholds).
- [ ] Declare the `order-confirmed.DLT` `KafkaTopic` CR and add it to
      `.agent/context/kafka-topics.md` (config repo).
- [ ] Demonstrate under chaos: poison message → DLT with no head-of-line blocking; dedup-DB
      outage → lag grows then recovers with no loss/duplicate; checkout stays green.
- [ ] Own the DLT replay/discard runbook.

## References

- ADR 0001 (trigger topic), ADR 0002 (dedup store)
- `CLAUDE.md`: idempotency, probe rules, structured concurrency / SIGTERM drain
- `.agent/context/kafka-topics.md` (config repo) — DLT topic to be added
- config-repo `docs/design/slo-definitions.md` — lag/DLT-depth SLOs
