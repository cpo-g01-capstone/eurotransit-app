# EuroTransit — Application Repository

Source code, tests, CI workflows, and k6 scripts for the five EuroTransit services.

## Services

| Service | Port | Responsibility |
|---------|------|----------------|
| catalog | 8081 | Lists products/offers |
| orders | 8082 | Orchestrates the purchase workflow |
| inventory | 8083 | Tracks finite seats |
| payments | 8084 | Authorizes payment |
| notifications | 8085 | Sends confirmations |

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

1. **changes** — detects which `*-service` modules changed (a root/`shared` change rebuilds all).
2. **build-test** — `gradle build` compiles the multi-module project and runs unit tests (PR gate);
   results are published as a JUnit check on the PR.
2b. **code-checks** — runs **detekt** static analysis via `config/detekt-init.gradle.kts`
   (advisory for now; reports uploaded as an artifact). Run locally with
   `gradle --init-script config/detekt-init.gradle.kts detekt`.
3. **images** (main only) — builds each changed service's boot jar, then builds and pushes an
   immutable image to **GHCR** (`ghcr.io/<owner>/eurotransit-<service>`), tagged with the 7-char
   Git SHA (`latest` only on `main`), with a build-provenance attestation.
4. **update-gitops** (main only) — bumps the image tags in
   `deploy/charts/eurotransit/values.yaml` in the configuration repository (via `CONFIG_REPO_PAT`).
   Argo CD detects the change and reconciles the cluster.

**CI never holds cluster credentials. Deployment happens through Git.**

### Required secrets

| Secret | Scope | Used by |
|--------|-------|---------|
| `GITHUB_TOKEN` | automatic (`packages: write`) | push images to GHCR |
| `CONFIG_REPO_PAT` | fine-grained PAT, `contents: read+write` on `eurotransit-config` only | the GitOps tag bump |

Until `CONFIG_REPO_PAT` (and the Helm chart `values.yaml`) exist, the `update-gitops` job
skips gracefully with a warning instead of failing.

## Team roles

See `docs/roles.md`.