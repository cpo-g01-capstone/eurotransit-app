# CLAUDE.md — EuroTransit Application Repository

Read this before generating any code, test, or CI workflow in this repository.
For the deployment side (Helm charts, Argo CD, Kafka CRs, SealedSecrets) see the
**configuration repository** `CLAUDE.md` and `.agent/`.

---

## What this repo owns

- Kotlin / Spring Boot source code for five services: catalog, orders, inventory, payments, notifications
- Gradle build files (Kotlin DSL)
- Dockerfiles (one per service, multi-stage)
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
- After push to ACR, CI commits updated image tags to the **configuration repository** using `CONFIG_REPO_PAT`
- `GITHUB_TOKEN` is not sufficient for cross-repo writes — use `CONFIG_REPO_PAT`

---

## Idempotency requirement

Every Kafka consumer handler in orders, inventory, payments, and notifications must be idempotent.
A message may be delivered more than once. Handlers must be safe to call multiple times with the same input.
See configuration repository `.agent/context/money-path.md` for the full deduplication scheme.

---

## Forbidden actions for AI in this repo

- Writing to `.github/CODEOWNERS` or `.claude/settings.json`
- Writing to `.env*` files or any file containing secrets
- Running `git push`, `git commit`, `kubectl`, `helm`, or `docker push`
- Adding cluster credentials to any workflow file
