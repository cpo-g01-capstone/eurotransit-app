# AGENTS.md — EuroTransit Application Repository

Canonical instructions for **any** coding agent (Cursor, Claude Code, Codex, etc.)
working in this repository. Read this file **first**, before generating any code, test,
or CI workflow. It tells you how to work, what to document, and what you must never do.

> This repo (`eurotransit-app`) owns **source code, tests, CI, and the justfile**.
> The deployment side (Helm charts, Argo CD, Kafka CRs, SealedSecrets) lives in the
> **configuration repository** `eurotransit-config`.

---

## 1. Read context in this order

Before producing any artifact, load the relevant context. Do not generate from
assumptions — the project is graded on operational correctness, not feature volume.

1. **`CLAUDE.md`** (this repo) — detailed technical rules: language/framework conventions,
   probe rules, CI rules, idempotency, naming, forbidden actions. This is the deep reference.
2. **`.agent/context.md`** (this repo) — what this repo owns vs. does not own.
3. **`docs/roles.md`** — who owns which area (the reviewer your PR must go through).
4. **Configuration repo `.agent/`** — for anything touching Kafka wiring, the money path,
   the DB schema, or delivery. Key files there:
   `.agent/context/money-path.md`, `.agent/context/kafka-topics.md`,
   `.agent/context/db-schema.md`, `.agent/agents/<role>-owner.md`.

If a task spans both repos, read both repos' agent files before starting.

---

## 2. What this repo owns

- Kotlin / **Spring Boot 3.x** source for five services: `catalog`, `orders`, `inventory`,
  `payments`, `notifications` (package root `com.eurotransit.<service>`, each a Gradle
  subproject under `backend/<service>/`)
- Async pipeline with **coroutines / Flows** (structured concurrency, no `GlobalScope`)
- Gradle build (Kotlin DSL), Dockerfiles (multi-stage, one per service)
- GitHub Actions CI (`.github/workflows/`)
- k6 load test scripts (`tests/k6/`)
- `justfile` — operational task runner

**Not owned here** (→ configuration repository only): Helm charts, Kubernetes manifests,
Argo CD config, SealedSecrets.

---

## 3. How to work (mandatory workflow)

Follow the project workflow from `KICKOFF.md §5`:

1. Pick up an issue from the project board; make sure it is **In Progress**.
2. Branch off `main`: `feature/<id>-short-desc`, `fix/<id>-...`, or `chore/<id>-...`.
   Never push directly to `main` — it is protected.
3. Develop locally; build and test with `just build` / `just test` (k3d for local cluster).
4. Write or update tests (JUnit 5, Kotlin) for every behavioural change.
5. Keep commits atomic — one logical concept per commit.
   Commit message format (English): `type(scope): description`
   e.g. `feat(orders): add idempotency key validation`.
6. Open a PR linked to the issue, describing the change and its rationale.
7. The PR requires **at least one human review**, preferably by the **role owner**
   (`docs/roles.md`). CI must be green before merge.
8. After merge, move the task to **Verify**, then **Done**.

Branches are short-lived: merge or rebase within 2–3 days.

---

## 4. Documentation duties (this is graded)

Documentation is a first-class deliverable. When your change affects any of the following,
update (or create) the corresponding doc **in the same PR**:

| If you change… | Update / create… | Repo |
|---|---|---|
| A meaningful architectural/technical decision | An ADR in `docs/adr/` (template: config repo `.agent/decisions/ADR-001-template.md`) | this or config |
| Service boundaries or sync/async split | `docs/design/service-boundaries.md` | config |
| Inventory consistency behaviour | `docs/design/consistency.md` (CAP/PACELC, both faces) | config |
| Idempotency / dedup scheme | `docs/design/idempotency.md` | config |
| SLO targets, SLIs, error budget | `docs/design/slo-definitions.md` | config |
| Team roles / ownership | `docs/roles.md` | this |
| Public usage / how to build & run | `README.md` | this |

**Agent-log (mandatory deliverable):** whenever a human catches an AI-generated artifact
that was wrong, unsafe, or subtly wrong (e.g. a non-idempotent handler, a liveness probe
checking a downstream), record it in `docs/agent-log.md` (config repo) using the template
in `KICKOFF.md §8`. The project requires **≥3 documented cases**.

Do **not** author or silently decide content that the AI policy reserves for humans:
service decomposition, consistency model, SLO values, failure-mode mapping, chaos
hypotheses, postmortems. You may draft scaffolding and propose options, but the team owns
the decision and the written justification.

---

## 5. Hard technical rules (reject violations)

These are non-negotiable. If you are about to generate something that breaks one, stop.
Full detail and rationale live in `CLAUDE.md`.

- **Liveness probes** check only the local process (`/actuator/health/liveness`).
  They must **never** check DB, Kafka, or any downstream. Readiness flips to refusing
  traffic during drain.
- Every service exposes `/actuator/health/liveness`, `/actuator/health/readiness`,
  `/actuator/prometheus`.
- **Every Kafka consumer handler is idempotent** — redelivery must be safe
  (no double-reserve, no double-charge). See config repo `.agent/context/money-path.md`.
- **Notifications failure must never fail checkout** (graceful degradation).
- **CI holds no cluster credentials.** Workflows must never contain `kubectl`,
  `helm upgrade`, `az aks`, or cluster creds. Image tag = short Git SHA (`${GITHUB_SHA::7}`);
  `docker push` only on `main`. Cross-repo writes to config use a short-lived
  GitHub App installation token (config-repo ADR 0007), never `GITHUB_TOKEN` or a PAT.
- Structured concurrency: one `CoroutineScope` per failure domain; cooperative cancellation
  on SIGTERM; no `GlobalScope`; no `runBlocking` outside bootstrap.

---

## 6. Forbidden actions for AI in this repo

Aligned with `.claude/settings.json` (the enforced permission boundary):

- Do **not** write to `.github/**`, `.claude/settings.json`, or `.env*` / `*.pem` / `*.key`.
- Do **not** run `git push`, `git commit`, `kubectl`, `helm`, `az`, `kubeseal`,
  `docker push`, or any `rm -rf` / piped-to-shell installer.
- Do **not** add cluster credentials to any workflow.
- Do **not** merge AI-generated code without human review — *"if you cannot explain why
  it works, do not merge it"* (`KICKOFF.md §8`).

### Blast radius

This agent can open PRs and modify source/test/CI files in this repo. All changes go through
human review + green CI before merge; no agent action reaches the cluster directly
(Argo CD pulls from the config repo). Worst case is contained by branch protection,
required review, and CI gates.

---

*Entry point for agents. Deep technical reference: `CLAUDE.md`. Project rationale and
roadmap: root `KICKOFF.md`. Keep this file in sync with `.claude/settings.json` and `CLAUDE.md`.*
