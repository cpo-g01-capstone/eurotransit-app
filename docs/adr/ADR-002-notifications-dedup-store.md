# ADR-002 — Notifications deduplication via a dedicated PostgreSQL store

**Date:** 2026-07-08
**Status:** Accepted
**Owner:** @marcodonatucci (proposed on behalf of the team; pending team ratification per AI usage policy)

## Context

Notifications consumes `order-confirmed` (ADR-001) with **at-least-once** delivery: the same
event can be redelivered on consumer rebalance, offset-commit gaps, or pod restart. Without
deduplication this produces **duplicate confirmation emails**.

`CLAUDE.md` mandates idempotency "at the Kafka consumer level **and** at the database level"
for money-path handlers. Today `notifications-service` is **stateless** (only `spring-kafka`
+ `webflux`, no datastore). The team's cross-cutting preference is **consistency over
availability**.

Options considered: in-memory dedup (lost on restart, does not satisfy DB-level dedup),
Redis/TTL cache (introduces a component outside the approved stack — Postgres only), and a
dedicated PostgreSQL store.

## Decision

Give Notifications its **own PostgreSQL database** (CloudNativePG, cluster
`eurotransit-notifications-db`) with a deduplication table keyed by the idempotency key.

- Composite idempotency key: `orderId` + event type `order-confirmed` (per ADR-001).
- Table (working name) `sent_notifications`, primary key on the idempotency key.
- The handler deduplicates **durably**: a conditional insert (insert-if-absent) decides
  whether this event has already been handled; the row is the durable record of handling.
- Consistent with `CLAUDE.md`: dedup at the consumer level (the handler check) **and** at
  the DB level (unique constraint / conditional insert).

## Rationale

- **Restart-safe invariant.** A durable store upholds *"at most one confirmation email per
  confirmed order"* even across pod death, rebalance, and redelivery — exactly what a
  consistency-over-availability stance calls for. In-memory dedup cannot: it forgets on
  restart and would resend.
- **Stack coherence.** Reuses PostgreSQL via the CloudNativePG operator already in the
  platform; introduces no new technology. Redis was rejected for this reason.
- **Auditability.** The table doubles as an observable record of which confirmations were
  handled/sent — useful for the demo and for verifying graceful-degradation catch-up.

## Consequences

**Easier:**
- Strong, restart-safe idempotency; satisfies the DB-level dedup requirement in `CLAUDE.md`.
- Aligns with the team's consistency preference; a clear invariant to demonstrate under chaos
  (e.g. Kafka partition / pod kill → no duplicate emails).

**Harder / follow-ups:**
- Notifications is **no longer stateless**. It needs: a `eurotransit-notifications-db`
  CloudNativePG cluster (config repo), a Flyway migration for `sent_notifications`, the
  operator-generated app secret (`eurotransit-notifications-db-app`), and R2DBC config
  (+ JDBC URL for Flyway, mirroring the Orders pattern). Extra `resources:` and a PDB decision.
- Config-repo naming (per `CLAUDE.md`): cluster `eurotransit-notifications-db`, services
  `eurotransit-notifications-db-rw` / `-ro`, secret `eurotransit-notifications-db-app`.
- The idempotency scheme for `order-confirmed` should be recorded in config-repo
  `docs/design/idempotency.md` (owner @MauroC0l) — that row is currently empty.

**Graceful-degradation caveat (must be honoured):**
This PostgreSQL is **Notifications' own** dependency, *downstream* of checkout on the money
path. Checkout has already completed at `order-confirmed`, so a Notifications-DB outage
**must not** propagate to checkout. What Notifications does when *its* DB is down (block and
let Kafka lag grow until the DB returns, vs. skip) is a **failure-handling / offset-commit**
decision, decided in **ADR-003** (block-and-lag: do **not** commit the offset, let events
accumulate as consumer lag, catch up when the DB recovers).

**Open sub-point (resolved in ADR-003):** the ordering of "record dedup row" vs "send
email" is not transactional (email is an external side effect). Insert-then-send risks a
recorded-but-unsent gap; send-then-insert risks a duplicate on crash. **ADR-003** resolves
this with a two-phase `pending → sent` status on the row (which is why the `sent_notifications`
Flyway migration must include a `status` column).

## References

- ADR-001 (trigger topic `order-confirmed`)
- `CLAUDE.md` idempotency rules; config-repo `docs/design/idempotency.md`
- Orders R2DBC + Flyway pattern: `backend/orders-service/src/main/resources/application.yml`
