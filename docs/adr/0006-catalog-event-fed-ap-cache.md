# ADR 0006 — Catalog: event-fed in-memory AP cache, no database

- **Status:** Accepted (team ratification 2026-07-17; decision 3's replay scheme
  already amended by ADR 0007 — see Supersedes below)
- **Date:** 2026-07-11
- **Deciders:** @giova95 (author); @marcodonatucci (catalog area); ratified by the team 2026-07-17
- **Context tags:** catalog, kafka, consistency, chaos
- **Supersedes / Superseded by:** decision 3's `auto.offset.reset=earliest`
  replay scheme and the static seed mirror are amended by ADR 0007 (snapshot
  hydration — the replay re-derived state from history and diverged permanently
  after out-of-band seat changes, issue #31)
- **Related:** `docs/design/consistency.md` + `service-boundaries.md` (config repo,
  the AP/EL vs CP/EC contrast this ADR implements); chaos experiment CE-1
  ("Catalog browsing stays healthy"); inventory `V2__seed_demo_routes.sql`

## Context

Catalog was scaffold-only (zero endpoints), which left two holes: CE-1's containment
claim had nothing to browse, and the documented consistency contrast — Catalog AP/EL
vs Inventory CP/EC — existed only on paper. The service spec: "lists products/offers;
mostly reads; **tolerant of staleness**".

## Decision

1. **No database.** Catalog serves browsing from an **in-memory advisory cache**
   (`RouteCache`). The authoritative seat count lives behind Inventory's CP
   reservation path; what Catalog shows is advisory by contract, so durable
   storage buys nothing. State is disposable — see (3) for why that is safe.
2. **The cache is fed by `inventory-reserved` events** — Catalog is a pure
   downstream reader of the money path, never a participant. A lost or lagging
   event means slightly staler advisory availability, which the contract accepts
   (eventual consistency, E→L in PACELC terms).
3. **Broadcast consumption, deliberately unlike the money-path consumers:**
   - **per-instance consumer group** (random suffix): every replica must see
     every event; a shared group would split the partition stream and replica
     caches would silently diverge — cache-building consumers are broadcast
     consumers;
   - **`auto.offset.reset=earliest`**: a fresh pod replays the topic and
     converges to the same state — this replay property is what makes the
     no-database choice safe (the topic is the durable store);
   - **no manual ack, no `processed_events` table, no error-handler chain**:
     correctness machinery exists to protect the money path; an advisory cache
     needs only a lifetime-scoped in-memory `reservationId` dedup set.
4. **Read endpoints are dependency-free** (no DB, no downstream calls): during
   CE-1, when Payments is degraded and the Orders breaker is open, this surface
   has no failure path in common with the money path — the containment claim is
   structural, not accidental. The baseline k6 thresholds make it executable
   (`catalog_healthy > 0.995`).

## Consequences

- The CAP/PACELC contrast is now demonstrable IN CODE at the oral: compare
  `InventoryKafkaConsumer` (CP: transactional dedup, manual ack, error-handler
  fallback) with `InventoryReservedListener` (AP: broadcast, best-effort).
- Restart behaviour: cache is empty for the seconds it takes to replay the topic
  — readiness does not gate on it (advisory data; empty list is a valid answer).
- Known simplification: the seed mirrors inventory's `V2__seed_demo_routes.sql`
  statically. A fuller system would hydrate from an Inventory snapshot API; the
  deterministic mirror keeps demos reproducible. If inventory's seed changes,
  update `RouteCache.init` in the same PR.
- Topic retention becomes a real parameter: replay-based rebuild assumes the
  events are still there (7-day retention today — fine for the course; a
  compacted availability topic would be the long-term answer).

## Alternatives considered

- **Dedicated Catalog DB (CNPG)**: durable, but adds a cluster + migrations to
  serve data that is advisory by contract; restart-replay already provides
  convergence. Rejected as cost without correctness benefit.
- **Catalog queries Inventory synchronously**: couples the browse surface to the
  money path's failure modes — exactly what CE-1 exists to forbid. Rejected.
- **Shared consumer group + sticky routing**: would balance load but split the
  event stream across replicas; divergent caches per replica. Rejected.
