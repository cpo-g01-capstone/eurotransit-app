# EuroTransit — application repository task runner
# Usage: just <recipe>

# List available recipes
default:
    @just --list

# ── Build ─────────────────────────────────────────────────────────────────────

# Build all services
build:
    ./gradlew build -x test

# Build a specific service (e.g. just build-service orders)
build-service service:
    ./gradlew :backend:{{service}}:build -x test

# ── Test ──────────────────────────────────────────────────────────────────────

# Run all tests
test:
    ./gradlew test

# Test a specific service
test-service service:
    ./gradlew :backend:{{service}}:test

# ── Local run ─────────────────────────────────────────────────────────────────

# Run a service locally (e.g. just run orders)
run service:
    ./gradlew :backend:{{service}}:bootRun

# ── Docker ────────────────────────────────────────────────────────────────────

# Build a container image (e.g. just image-build orders)
image-build service:
    docker build -t eurotransit-{{service}}:local ./backend/{{service}}

# Run a container image locally
image-run service:
    docker run --rm -p 8080:8080 eurotransit-{{service}}:local

# ── Verification ──────────────────────────────────────────────────────────────

# Full local verification: build → test → image → health check
verify service:
    just build-service {{service}}
    just test-service {{service}}
    just image-build {{service}}
    docker run -d --name et-verify-{{service}} -p 8080:8080 eurotransit-{{service}}:local
    sleep 5
    curl -f http://localhost:8080/actuator/health/readiness || (docker stop et-verify-{{service}} && exit 1)
    docker stop et-verify-{{service}}
    docker rm et-verify-{{service}}
    @echo "✅ {{service}} verification passed"

# Lint Helm chart (requires config-repo checked out as sibling directory)
helm-lint:
    helm lint ../config-repo/deploy/charts/eurotransit

# ── k6 load tests ─────────────────────────────────────────────────────────────

# Baseline traffic (3 VUs, 3 minutes)
load-baseline host="https://gXX.cpo2026.it":
    BASE_URL={{host}} VUS=3 DURATION=3m k6 run tests/k6/baseline.js

# Latency injection (800ms delay)
load-latency host="https://gXX.cpo2026.it":
    BASE_URL={{host}} VUS=3 DURATION=4m DELAY_MS=800 k6 run tests/k6/baseline.js

# Error injection (20% failure rate)
load-errors host="https://gXX.cpo2026.it":
    BASE_URL={{host}} VUS=3 DURATION=4m FAIL_RATE=0.20 k6 run tests/k6/baseline.js
