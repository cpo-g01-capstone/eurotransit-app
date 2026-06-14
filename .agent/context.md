# Agent context — Application repository

The authoritative agent context lives in the **configuration repository** under `.agent/`.
Before generating artifacts that touch delivery, Kafka wiring, or Helm charts, read that file.

## What this repo owns

- Source code for the five services (Kotlin / Spring Boot / Gradle)
- Container image build logic (Dockerfile per service)
- CI workflows (`.github/workflows/`) — build, test, push to ACR, update config-repo
- k6 load test scripts (`tests/k6/`)
- `justfile` — the operational task runner

## What this repo does NOT own

- Helm charts → configuration repository `deploy/charts/eurotransit/`
- Kubernetes manifests → configuration repository
- Argo CD Application → configuration repository
- SealedSecrets → configuration repository

## Key constraints for AI in this repo

- CI workflows must NEVER contain `kubectl`, `helm upgrade`, or cluster credentials
- Image tags use short Git SHA: `${GITHUB_SHA::7}`
- Every service exposes `/actuator/health/liveness` and `/actuator/health/readiness`
- Liveness probes check only the local process — never a downstream dependency
- Every Kafka consumer handler must be idempotent (see config-repo `.agent/context/money-path.md`)
