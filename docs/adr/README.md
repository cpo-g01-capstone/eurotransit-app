# Architecture Decision Records

Team-owned architecture decisions for the EuroTransit **application** services. Each ADR
captures one decision, its context, the alternatives, and its consequences. Platform and
deployment decisions live in the **configuration repository** `docs/adr/`.

## Conventions

- One file per decision: `NNNN-kebab-title.md` (zero-padded, e.g. `0001-notifications-trigger-topic.md`).
- Start from [`template.md`](template.md).
- Status lifecycle: **Proposed** → **Accepted** (after team ratification) → **Superseded** (link the successor).
- ADRs are immutable once Accepted — to change a decision, write a new ADR that supersedes the old one.
- Decisions drafted with agent assistance must include a **Verification & ownership** section per the agentic-coding policy.

## Index

| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-notifications-trigger-topic.md) | Notifications consumes `order-confirmed` directly (not `notification-requested`) | Accepted | 2026-07-08 |
| [0002](0002-notifications-dedup-store.md) | Notifications deduplication via a dedicated PostgreSQL store | Accepted | 2026-07-08 |
| [0003](0003-notifications-failure-handling.md) | Notifications failure handling & offset-commit semantics (at-least-once + DLT) | Accepted | 2026-07-08 |
| [0004](0004-notifications-probes-lifecycle.md) | Notifications probes & async lifecycle (readiness reflects lifecycle only) | Accepted | 2026-07-08 |
| [0005](0005-orders-canonical-implementation-and-graceful-shutdown.md) | Canonical Orders implementation and the graceful-shutdown pattern | Accepted | 2026-07-11 |
| [0006](0006-catalog-event-fed-ap-cache.md) | Catalog: event-fed in-memory AP cache, no database | Accepted | 2026-07-11 |
| [0007](0007-catalog-snapshot-hydration.md) | Catalog cache: Inventory snapshot hydration + latest-offset consumption | Approved | 2026-07-13 |
| [0008](0008-notifications-kafka-consumer-metrics.md) | Notifications Kafka consumer metrics: replay Boot's `DefaultKafkaConsumerFactoryCustomizer` | Proposed | 2026-07-15 |
