# ADR 0007 — Catalog cache: Inventory snapshot hydration + latest-offset consumption

- **Status:** Proposed
- **Date:** 2026-07-13
- **Deciders:** @vojtech-n (author); team to ratify
- **Context tags:** catalog, kafka, consistency, chaos
- **Supersedes / Superseded by:** amends ADR 0006 (decision 3, the
  `auto.offset.reset=earliest` replay scheme and the static seed mirror)
- **Related:** issue #31; CE-2 run 3 (config repo,
  `docs/chaos-experiments/ce-2/ce-2-pod-kill-inventory-run-3.md`); config repo
  `docs/design/data-flow.md` (edge 3b)

## Context

ADR 0006 rebuilt the advisory cache on restart by replaying `inventory-reserved`
from the earliest offset on top of a hardcoded seed mirror. That re-derives seat
state **from event history**, which is only correct while the event stream is the
sole writer of seat state. It is not: `just seed-db` reseeds inventorydb directly,
and any manual capacity fix would too. CE-2 run 3 showed the consequence — the
frontend displayed the Turin–Milan demo route as sold out while inventorydb had it
at 100/100, and every catalog restart replayed thousands of historical events and
clamped the route straight back to 0. The divergence was permanent, which breaks
the "eventually consistent" half of the AP/EL contract ADR 0006 promised
("stale-then-convergent" became "stale-forever").

## Decision

1. **Hydrate from an Inventory snapshot at startup.** Inventory exposes a
   read-only `GET /inventory/routes` (`RouteSnapshotController`); Catalog's
   `RouteCacheHydrator` fetches it after `ApplicationReadyEvent` and replaces the
   cache wholesale (`RouteCache.hydrate`). Snapshot = authoritative state *now*,
   regardless of how it came to be (events, reseeds, manual SQL).
2. **Consume from the current offset, not history:** the listener's per-instance
   group now uses `auto.offset.reset=latest`. Events are deltas on top of the
   snapshot baseline; replaying history on top of a current snapshot would
   double-count it.
3. **Hydration is best-effort and startup-only, preserving ADR 0006's CE-1
   containment claim.** The browse endpoints still serve purely from memory with
   no downstream call per request. If Inventory is unreachable at startup (the
   CE-2 scenario), Catalog still comes up and serves the hardcoded seed
   fallback, and the hydrator retries with capped backoff until it succeeds
   once. Readiness does not gate on hydration.

## Consequences

- After a `just seed-db` reseed, **restarting catalog now converges** the
  advisory view to inventorydb — previously restarting made it worse (issue #31
  acceptance criteria).
- The hardcoded seed in `RouteCache.init` is demoted from "the state" to a
  pre-hydration fallback; drift between it and inventory's migrations now
  self-heals at first hydration instead of being load-bearing.
- Topic retention is no longer a rebuild dependency (ADR 0006 noted 7-day
  retention as a real parameter of the replay scheme; a compacted topic is no
  longer the long-term answer — the snapshot is).
- Accepted race: an event landing between the snapshot read and its apply can be
  absorbed by the snapshot (which already includes its effect — Inventory
  commits before publishing) or cost one event of advisory staleness. Within
  contract.
- Known limitation: a reseed **without** a catalog restart is invisible until
  the next restart (hydration is startup-only). Acceptable per issue #31's
  acceptance criteria; a periodic or admin-triggered re-hydration is the obvious
  extension if it ever isn't.
- Catalog gains a startup-time (not request-time) config dependency on the
  Inventory service URL: `inventory.base-url`, in-cluster default
  `http://eurotransit-inventory`, override via `INVENTORY_BASE_URL` locally.
  The existing intra-app NetworkPolicy (config repo, `eurotransit-allow-intra-app`)
  already permits the call; no config-repo change is required.

## Verification & ownership (agentic-coding policy)

Drafted with agent assistance. Before ratification the team must verify:

- [ ] Issue #31 acceptance criteria on the cluster: `just seed-db <scenario>` +
  catalog pod restart → `GET /api/catalog` availability matches inventorydb for
  the seeded routes;
- [ ] mid-run catalog restart converges (no replay double-decrement) — rerun the
  CE-2 run-3 reviewer reproduction;
- [ ] catalog starts and serves the seed fallback with inventory scaled to 0,
  then hydrates once inventory returns (retry path);
- [ ] the ingress already routes `PathPrefix(/api/inventory)` north-south
  (config repo `ingress.yaml`), so `GET /api/inventory/routes` becomes publicly
  reachable — confirm the team accepts that (it serves the same fields
  `/api/catalog` already shows, now with authoritative counts).

## Alternatives considered

- **Keep replay-from-earliest, document "reseed requires topic reset"**: resetting
  or truncating a shared topic to fix a browse cache is operationally absurd and
  still breaks on any manual capacity change. Rejected.
- **Compacted availability topic (Inventory publishes absolute counts)**: the
  cleanest event-sourced answer, but requires a new topic, a new producer path in
  the money-path service, and reseed tooling would still have to publish through
  it. Snapshot hydration fixes the defect without touching the money path.
  Revisit if Catalog ever needs multi-entity state.
- **Per-request read-through to Inventory**: rejected in ADR 0006 and still
  rejected — couples browse to the money path's failure modes (CE-1 forbids it).
  Startup-only hydration with a serving fallback does not.
