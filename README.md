# EuroTransit — Application Repository

Source code, tests, CI workflows, and k6 scripts for the five EuroTransit backend services and the
frontend SPA.

## Services

| Service | Port | Responsibility |
|---------|------|----------------|
| catalog | 8081 | Lists products/offers |
| orders | 8082 | Orchestrates the purchase workflow |
| inventory | 8083 | Tracks finite seats |
| payments | 8084 | Authorizes payment |
| notifications | 8085 | Sends confirmations |

Backend services are Kotlin / Spring Boot Gradle subprojects under `backend/<service>/`.

| Frontend | Port | Responsibility |
|----------|------|----------------|
| frontend | 8080 (container) / 5173 (dev) | React SPA — the only thing the customer sees |

The SPA lives in [`frontend/`](frontend/) and has its own [README](frontend/README.md) and
[design doc](frontend/docs/2026-07-12-frontend-design.md). It is built, tested and shipped by this
repo exactly like a backend service: its own CI jobs (`frontend-checks`, `image-frontend`), its own
image in ACR, and its own Deployment in the config-repo chart.

## Money path in one line

`POST /orders` → `order-placed` → Inventory reserves → `inventory-reserved` → Orders **calls Payments
synchronously** (`POST /payments/authorize`, circuit breaker — config-repo ADR 0018) →
`payment-authorized` → `order-confirmed` → Notifications. Payments has no Kafka consumer.
Full trace: config-repo `.agent/context/money-path.md`.

## Notifications service (Kafka consumer)

The terminal, fully-asynchronous stage of the money path. It consumes `order-confirmed` and
sends an order-confirmation notification. Design decisions are recorded in `docs/adr/ADR-001..004`.

- **Trigger:** consumes `order-confirmed` only (ADR-001).
- **Idempotency:** a dedicated PostgreSQL table `sent_notifications` deduplicates by `order_id`
  with a two-phase `PENDING → SENT` row (ADR-002/003), so redelivery never sends twice.
- **Delivery:** at-least-once with a manual (`RECORD`) ack; send failures are retried and then
  routed to `order-confirmed.DLT`; a transient dedup-DB outage blocks-and-lags (does not drop).
- **Sending:** a log-based stub (`LoggingEmailSender`) with a `notifications_sent_total` metric —
  the project grades resilience, not real e-mail.
- **Graceful degradation:** Notifications reaches the pipeline only through Kafka, so a full
  outage never fails checkout.

Run its tests (requires Docker for Testcontainers PostgreSQL; an embedded Kafka broker is used):

```bash
./gradlew :backend:notifications-service:test
```

> On macOS with Docker Desktop, the test task auto-detects the Docker Desktop raw socket and pins
> the Docker API version / disables Ryuk so Testcontainers works through the API proxy. This is
> guarded and a no-op on CI/Linux.

## Prerequisites

- JDK 21
- Docker
- [just](https://github.com/casey/just)
- [k6](https://k6.io/docs/get-started/installation/) (for load tests)

## Common tasks

```bash
just build                  # build all services
just test                   # run all tests
just run orders             # run orders service locally
just image-build orders     # build orders container image
just verify orders          # full local verification (build → test → image → health)
just load-baseline          # baseline k6 traffic against the deployed cluster
```

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`) runs on every PR and on push to `main`:

1. **changes** — detects which `*-service` modules changed (a root/`shared` change rebuilds all)
   and whether the frontend changed.
2. **build-test** — `gradle build` compiles the multi-module project and runs unit tests (PR gate);
   results are published as a JUnit check on the PR.
2b. **code-checks** — runs **detekt** static analysis via `config/detekt-init.gradle.kts`
   (advisory for now; reports uploaded as an artifact). Run locally with
   `gradle --init-script config/detekt-init.gradle.kts detekt`.
2c. **frontend-checks** — the SPA's PR gate: lockfile-exact install, `tsc` typecheck, unit tests,
   oxlint (including XSS-sink rules), production build.
3. **images** (main only) — builds each changed service's boot jar, then builds and pushes an
   immutable image to **ACR** (`acreurotransitg01.azurecr.io/eurotransit/<service>`), tagged with
   the 7-char Git SHA, with a build-provenance attestation. Registry auth is **Azure OIDC
   federation** (`azure/login` + `az acr login`) — no registry password (config-repo ADR 0010).
3b. **image-frontend** (main only) — the same, for the SPA image.
4. **update-gitops** (main only) — bumps the image tags in
   `deploy/charts/eurotransit/values.yaml` in the configuration repository via a short-lived
   **GitHub App installation token** (config-repo ADR 0007). Argo CD detects the change and
   reconciles the cluster.
   It `needs` both image jobs: either may legitimately be skipped when nothing in its paths
   changed, but a **failed** one blocks the write-back for everything — a green run must mean the
   tags point at images that were actually pushed.

**CI never holds cluster credentials. Deployment happens through Git.**

### Required secrets

| Secret | Scope | Used by |
|--------|-------|---------|
| `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_SUBSCRIPTION_ID` | OIDC federated identity, AcrPush only (config-repo ADR 0010, `infra/acr-oidc/`) | `azure/login` for the ACR push |
| `CONFIG_REPO_APP_ID` / `CONFIG_REPO_APP_PRIVATE_KEY` | GitHub App, Contents: write on `eurotransit-config` only (config-repo ADR 0007, `infra/gitops-writeback-app/`) | minting the short-lived token for the GitOps tag bump |

`GITHUB_TOKEN` is never used for cross-repo writes, and no personal PAT exists anywhere in the
pipeline. If the GitHub App secrets are missing, the `update-gitops` job skips gracefully with a
warning instead of failing.

## Team roles

See `docs/roles.md`.