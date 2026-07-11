# ADR 0004 — Notifications probes & async lifecycle (readiness reflects lifecycle only)

- **Status:** Accepted (runBlocking bridge ratified as D5, 2026-07-11 — see note)
- **Date:** 2026-07-08
- **Deciders:** _@marcodonatucci (drafted on behalf of the team, notifications); full team to ratify per AI-usage policy_
- **Context tags:** notifications, probes, lifecycle, async, Pillar A, Pillar C
- **Supersedes / Superseded by:** —

---

> **Implementation note (RATIFIED — decision D5, 2026-07-11):** the `order-confirmed` handler
> could not be a `suspend` @KafkaListener — in this Spring Kafka version that swallows handler
> exceptions so retries/DLT never fire (agent-log Case 5). It is implemented as a non-suspend
> handler that takes the raw `ConsumerRecord` and bridges to the suspending service with
> `runBlocking`. The team ratified this as the **standard pattern for every @KafkaListener that
> needs error-handler semantics** — the one sanctioned exception to the `CLAUDE.md` "no
> runBlocking outside bootstrap" rule (the consumer thread is a dedicated blocking poll loop, so
> blocking there is correct). In use in: notifications (`OrderConfirmedListener`), orders
> (`InventoryReservedConsumer`), inventory (`OrderFailedConsumer`). The `CoroutineScope` failure
> domain still exists and is used by the error-handler recoverer.

## Context

Notifications is a **pure Kafka consumer**: it serves no HTTP money-path traffic, sits
behind no Traefik route, and is **off the critical path** (checkout completes at
`order-confirmed`). `CLAUDE.md` mandates: liveness checks only the local process;
structured concurrency with one `CoroutineScope` per failure domain; cooperative
cancellation on SIGTERM; readiness flips to REFUSING while draining.

Open question: should **readiness** also reflect **Kafka / dedup-DB connectivity**, or only
the process lifecycle?

## Decision

Option A — lifecycle only:

- **Liveness** (`/actuator/health/liveness`): local process only. Never checks Kafka, the
  dedup DB, or any downstream (non-negotiable rule).
- **Readiness** (`/actuator/health/readiness`): reflects **lifecycle only** — UP once the app
  has started and the consumer is running; flips to REFUSING (`OUT_OF_SERVICE`) during the
  SIGTERM drain. It does **not** include Kafka or the dedup DB.
- **Async lifecycle:** one `CoroutineScope(SupervisorJob() + Dispatchers.IO)` as the failure
  domain; on SIGTERM → stop the Kafka listener container, cancel the scope, let in-flight
  handlers drain, then exit. No `GlobalScope`; no `runBlocking` outside bootstrap.

Why lifecycle-only:

- Readiness in k8s gates `Service` traffic and rolling updates. Notifications has **no
  inbound Service traffic**, so readiness's only real job here is the **drain signal**.
- Binding readiness to a downstream would flap and **stall rollouts** on a transient Kafka/DB
  hiccup — the same anti-pattern as a liveness probe checking downstream (a documented
  agent-log trap in `CLAUDE.md`).
- DB-down is already handled by **block-and-lag** (ADR 0003) and Kafka reconnect is automatic;
  neither needs readiness to react. Operators observe these via consumer-lag and DLT metrics
  with symptom-based alerts, not via the readiness probe.

## Alternatives considered

- **Readiness includes Kafka / dedup-DB connectivity** — rejected: it would flap and stall
  rolling updates on transient downstream hiccups, replicating the forbidden liveness-checks-
  downstream anti-pattern. DB outages are covered by block-and-lag (ADR 0003) and Kafka
  reconnect is automatic, so readiness has nothing useful to add here.

## Consequences

**Easier**
- No probe flapping; clean rolling updates; drain works via Spring's `ReadinessState`.
- Consistent with the liveness philosophy (process health ≠ downstream health).

**Harder / follow-ups (implementation traps)**
- Spring Boot's **default** probe groups are already correct for this service: readiness
  group = `{ readinessState }`, liveness group = `{ livenessState }`; the R2DBC and Kafka
  `HealthIndicator`s live in the aggregate `/actuator/health` but are **not** in the
  readiness group unless explicitly added. **The requirement is therefore to NOT add
  `db`/`kafka` to the readiness group** (`management.endpoint.health.group.readiness.*`).
  Keep the default; do not "helpfully" wire downstream indicators into readiness.
- Chart probes (config repo): readiness `path: /actuator/health/readiness`, liveness
  `path: /actuator/health/liveness`, with `resources:` set.
- The SIGTERM drain must be **demonstrated** (money-path DoD, Pillar A): in-flight handlers
  finish or cancel cleanly, no orphaned tasks, no double-processing, readiness refuses during
  drain.

## Verification & ownership (agentic-coding policy)

This decision was drafted with agent assistance and **must be verified by the team** before
ratification:

- [x] Ratify (or replace) the non-suspend `ConsumerRecord` + `runBlocking` bridge that
      deviates from the "no runBlocking outside bootstrap" rule (agent-log Case 5).
      **Ratified as D5 on 2026-07-11** — see the implementation note above for scope.
- [ ] Confirm `db`/`kafka` are **not** wired into the readiness health group
      (`management.endpoint.health.group.readiness.*`); keep the Spring Boot default.
- [ ] Demonstrate the SIGTERM drain: in-flight handlers finish or cancel cleanly, no orphaned
      tasks, no double-processing, readiness refuses during drain (money-path DoD, Pillar A).

## References

- ADR 0003 (block-and-lag on DB outage; automatic Kafka reconnect)
- `CLAUDE.md`: probe rules, async lifecycle requirements, "common mistakes to reject"
- `backend/notifications-service/src/main/resources/application.yml` (probes already enabled)
- agent-log Case 5 (config repo) — the suspend-listener exception-swallowing trap.
