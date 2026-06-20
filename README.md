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

CI builds and tests each service, then pushes immutable images to ACR.
After a successful push to `main`, CI commits new image tags to the configuration repository.
Argo CD detects the change and reconciles the cluster.

**CI never holds cluster credentials. Deployment happens through Git.**

## Team roles

See `docs/roles.md`.