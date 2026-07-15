# CLAUDE.md — EuroTransit Application Repository

Read this before generating any code, test, or CI workflow in this repository.
For the deployment side (Helm charts, Argo CD, Kafka CRs, SealedSecrets) see the
**configuration repository** `CLAUDE.md` and `.agent/`.

---

## What this repo owns

- Kotlin / Spring Boot source code for five backend services: catalog, orders, inventory, payments, notifications
- The frontend SPA (`frontend/` — React + Vite + TypeScript); see `frontend/README.md`
- Gradle build files (Kotlin DSL)
- Dockerfiles (one per service, multi-stage, plus the frontend)
- GitHub Actions CI workflow
- k6 load test scripts
- `justfile` — operational task runner

## What this repo does NOT own

Helm charts, Kubernetes manifests, Argo CD config, SealedSecrets → **configuration repository only**

---

## Language and framework conventions

- **Kotlin** with coroutines and Flows for the async pipeline (orders, notifications)
- **Spring Boot 3.x** with Gradle Kotlin DSL
- **JUnit 5** for all tests, written in Kotlin
- Package root: `com.eurotransit.<service>`
- Each service is a separate Gradle subproject under `backend/<service>/`

---

## Probe rules (non-negotiable)

```yaml
# CORRECT — liveness checks only the local process
livenessProbe:
  httpGet:
    path: /actuator/health/liveness

# WRONG — liveness must never check DB, Kafka, or any downstream
# If you generate a liveness probe hitting /actuator/health (which includes DB), reject it
```

Every service must expose:
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`

---

## CI rules (non-negotiable)

- Workflows must NEVER contain `kubectl`, `helm upgrade`, `az aks`, or cluster credentials
- Image tag = short Git SHA (`${GITHUB_SHA::7}`)
- `docker push` goes to ACR only on push to `main`, not on PRs
- ACR auth is Azure OIDC federation (`azure/login` + `az acr login`) — no registry password (config-repo ADR 0010)
- After push to ACR, CI commits updated image tags to the **configuration repository** using a
  short-lived **GitHub App installation token** (`actions/create-github-app-token`, secrets
  `CONFIG_REPO_APP_ID` / `CONFIG_REPO_APP_PRIVATE_KEY` — config-repo ADR 0007)
- `GITHUB_TOKEN` is not sufficient for cross-repo writes, and personal PATs are forbidden — use the GitHub App token

---

## Idempotency requirement

Every Kafka consumer handler must be idempotent: a message may be delivered more than once, so
handlers must be safe to call multiple times with the same input. The consumers are in **orders,
inventory, notifications and catalog** — **payments has no Kafka consumer** (since config-repo
ADR 0018 it is reached by a synchronous HTTP call from Orders; its idempotency is the
`Idempotency-Key` header + the `payment_intents` unique indexes).

Catalog is the one deliberate exception to consumer-level dedup: its AP cache tolerates a skipped or
replayed event (ADR 0006).

The synchronous `POST /orders` and `POST /payments/authorize` must be idempotent too, keyed by the
`Idempotency-Key` header.

See configuration repository `.agent/context/money-path.md` (flow + keys) and
`docs/design/idempotency.md` (per-consumer scheme) for the full deduplication design.

---

## Forbidden actions for AI in this repo

- Writing to `.github/CODEOWNERS` or `.claude/settings.json`
- Writing to `.env*` files or any file containing secrets
- Running `git push`, `git commit`, `kubectl`, `helm`, or `docker push`
- Adding cluster credentials to any workflow file
