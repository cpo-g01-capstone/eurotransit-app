# ADR 0008 — Notifications Kafka consumer metrics: replay Boot's `DefaultKafkaConsumerFactoryCustomizer`

- **Status:** Proposed
- **Date:** 2026-07-15
- **Deciders:** _@marcodonatucci (drafted on behalf of the team, notifications); full team to ratify per AI-usage policy_
- **Context tags:** kafka, notifications, observability, metrics, micrometer
- **Supersedes / Superseded by:** —

---

## Context

`notifications-service` published zero `kafka_consumer_*` metrics to Prometheus, even though its
consumer group (`notifications`) was demonstrably live — broker-side
`kafka-consumer-groups.sh --describe` showed committed offsets advancing on all three
`order-confirmed` partitions, and the pod's other Micrometer metrics (HTTP, tracing) worked
normally. Verified 2026-07-13 during the CE-1 run-5 review:

```
count by (__name__) ({job="eurotransit-notifications", __name__=~"kafka_consumer.*"})
# → empty. The same query for orders/inventory/catalog returns the full metric set.
```

Impact: the RED dashboard's **"Kafka pipeline — consumer records lag"** panel and the
`KafkaConsumerLagHigh` `PrometheusRule` both aggregate
`kafka_consumer_fetch_manager_records_lag{namespace="eurotransit"}`
(config repo: `deploy/charts/eurotransit/templates/orders/prometheusrule.yaml`) — with
notifications absent from that metric family, its consumer falling behind is structurally
invisible to both. This is the second time this exact alert has been found silently mute
(the first, `adversarial-audit fix #52`, was a query bug — the metric it referenced,
`kafka_consumergroup_lag`, requires a broker-side exporter that was never deployed; this time
the metric name is right, but one producer of it — notifications — never publishes any series
at all). It matters specifically for notifications because the service is the fleet's
designated graceful-degradation point (ADR 0003: "Notifications can fail entirely without
failing checkout"). Growing lag on `order-confirmed` is *precisely* the symptom that
degradation is in progress, and it was the one signal nobody could see or alert on.

**Root cause.** `orders`/`inventory`/`catalog` get their `kafka_consumer_*` metrics from Spring
Boot's autoconfigured `ConsumerFactory` bean, which Boot wires with a `MicrometerConsumerListener`
whenever a `MeterRegistry` is on the classpath. Notifications needs things that autoconfiguration
doesn't expose — `ErrorHandlingDeserializer`, a `DeadLetterPublishingRecoverer`, and a
non-default `AckMode`/backoff policy driving the at-least-once + DLT scheme decided in ADR 0003
— so `KafkaConfig` defines its own `@Bean ConsumerFactory`
(`backend/notifications-service/.../config/KafkaConfig.kt`). That's a legitimate need, but
Boot's own `ConsumerFactory` `@Bean` method is `@ConditionalOnMissingBean` — the moment
notifications supplies its own, Boot's method (and everything it would otherwise have wired in,
including the Micrometer listener) never runs. This is not the first time this exact class of
gap has bitten this file: `application.yml` already carries a comment noting that
`spring.kafka.listener.observation-enabled` (tracing) silently does nothing here for the same
reason, and had to be re-enabled in code (`factory.containerProperties.isObservationEnabled =
true`) as a one-off patch.

## Decision

Keep the hand-built `ConsumerFactory` (it is still required — see Alternatives), but stop
letting it silently diverge from what Boot would have configured. Inject Boot's own
`DefaultKafkaConsumerFactoryCustomizer` beans and replay them against the manually constructed
factory before returning it:

```kotlin
@Bean
fun consumerFactory(
    props: KafkaProperties,
    customizers: ObjectProvider<DefaultKafkaConsumerFactoryCustomizer>,
): ConsumerFactory<String, OrderConfirmedEvent> {
    val config = props.buildConsumerProperties(null).toMutableMap()
    config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
    val json = JsonDeserializer(OrderConfirmedEvent::class.java).apply {
        setUseTypeHeaders(false)
        addTrustedPackages("com.eurotransit.notifications")
    }
    val factory = DefaultKafkaConsumerFactory(
        config,
        StringDeserializer(),
        ErrorHandlingDeserializer(json),
    )
    customizers.orderedStream().forEach { it.customize(factory) }
    return factory
}
```

`DefaultKafkaConsumerFactoryCustomizer` is the extension point Spring Boot ships specifically
for "I must define my own `ConsumerFactory` bean but still want Boot's own customizations
applied to it." Today, the only customizer Boot registers under this app's classpath
(`spring-kafka` + `micrometer-registry-prometheus` + `spring-boot-starter-actuator`, all already
dependencies — no `build.gradle.kts` change needed) is the Micrometer one, which calls
`factory.addListener(MicrometerConsumerListener(meterRegistry))` — the same call orders,
inventory, and catalog get for free. Replaying it here produces the exact same
`kafka_consumer_*` series, with the same tags, that the other three services already emit.

Verification added, two layers:

- `KafkaConsumerFactoryMicrometerBindingTest`
  (`backend/notifications-service/.../config/KafkaConsumerFactoryMicrometerBindingTest.kt`) —
  Docker-free, no broker, no Spring Boot app context. Boots Spring Boot's **real**
  `KafkaMetricsAutoConfiguration` (decompiled to confirm: its `kafkaConsumerMetrics` bean is a
  `DefaultKafkaConsumerFactoryCustomizer` whose `customize()` calls
  `factory.addListener(new MicrometerConsumerListener<>(meterRegistry))` — package
  `org.springframework.kafka.core`, spring-kafka 3.3.10) via `ApplicationContextRunner`, calls
  `KafkaConfig().consumerFactory(...)` directly with that real customizer, and asserts the
  returned factory's `listeners` contains a `MicrometerConsumerListener`. **Executed locally,
  passing** (`BUILD SUCCESSFUL`, 1 test, 0 failures, ~0.7s). Regression-proven: with the fix
  temporarily reverted (`git stash` on `KafkaConfig.kt` alone), this test does not even
  **compile** against the old single-parameter `consumerFactory(props)` — the old signature has
  no way to accept a customizer at all, which is the sharpest possible confirmation that the gap
  was structural, not incidental.
- `KafkaConsumerMetricsIT`
  (`backend/notifications-service/.../config/KafkaConsumerMetricsIT.kt`) — end-to-end: produces
  one `order-confirmed` record against the existing `@EmbeddedKafka` harness and asserts a
  `kafka.consumer.fetch.manager.records.lag` meter appears in the autowired `MeterRegistry`.
  Compiles, but **not executed** in the drafting environment — see Consequences.

## Alternatives considered

- **Register `MicrometerConsumerListener` directly** (`factory.addListener(MicrometerConsumerListener(meterRegistry))`,
  no customizer indirection) — functionally identical output, same line count, no new
  dependency. Rejected as the primary fix only narrowly: it hard-codes today's one Boot
  customization into app code rather than asking Boot for whatever it currently provides, so it
  would need a human to notice and hand-port the *next* autoconfigured behavior Boot adds later
  — exactly the failure mode that produced this issue and the earlier tracing gap. Kept as the
  documented fallback if the team ever prefers explicitness over relying on a Boot-internal
  extension point (see "Verification & ownership").
- **Stop replacing Boot's `ConsumerFactory`/listener-container-factory entirely** — configure the
  deserializer and trusted packages via `spring.kafka.consumer.*`/`spring.kafka.properties.*`
  properties, keep only a standalone `DefaultErrorHandler` bean (the pattern
  `orders-service/.../config/KafkaErrorHandlingConfig.kt` already uses), and get the
  `AckMode`/observation tweaks from Boot-supported container customizer hooks. This is the most
  root-cause fix — it would also let the `isObservationEnabled` code-level workaround be deleted
  — but it rewrites the consumer/container wiring that ADR 0003's DLT and backoff scheme depends
  on, and needs full re-verification against `OrderConfirmedDltIT` and `RedeliveryIT`. Rejected
  *for this issue* as disproportionate — a metrics-visibility bug does not justify that blast
  radius — but tracked as a legitimate future refactor (see Consequences).
- **Bind `io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics` manually per consumer
  instance** (e.g. via a rebalance/container-lifecycle callback) — rejected: `MicrometerConsumerListener`
  already is this, plus correct bind-on-create/unbind-on-close lifecycle handling across
  rebalances and container restarts. Reimplementing it by hand only reintroduces the
  bind/unbind bookkeeping for no benefit.
- **Deploy a broker-side exporter (`kafka-exporter`) and point a new panel/alert at
  `kafka_consumergroup_lag`** — rejected as a substitute for this fix: it's a different metric
  name, so it does not populate the *existing* RED panel or `KafkaConsumerLagHigh` alert without
  a config-repo change, violating this issue's "no config-repo change needed" acceptance
  criterion (both already aggregate namespace-wide with no `job=` filter — the only thing
  missing was notifications emitting the series). It is also the metric this fleet already tried
  and abandoned once (`adversarial-audit fix #52`, `prometheusrule.yaml` comment) specifically
  because no exporter was deployed. It remains valuable for a *different* problem this ADR does
  not solve — see Consequences — and is left to a separate decision, per the original issue.

## Consequences

**Easier**
- `kafka_consumer_fetch_manager_records_lag{job="eurotransit-notifications", topic="order-confirmed"}`
  (and the rest of the `kafka_consumer_*` family) is now emitted, with zero config-repo changes:
  the RED dashboard panel and `KafkaConsumerLagHigh` already aggregate namespace-wide.
- Growing lag on `order-confirmed` — the direct symptom of notifications degrading, per ADR 0003
  — is now visible and alertable, closing the blind spot that motivated this issue.
- The fix generalizes: any future Boot-registered `DefaultKafkaConsumerFactoryCustomizer` (metrics
  is the only one today) is inherited automatically, without another silent gap or another ADR.

**Harder / follow-ups**
- The hand-built `ConsumerFactory` remains a structurally special case that has now needed two
  patches (observation, metrics) to stay aligned with Boot's autoconfiguration. The "stop
  replacing Boot's factory" alternative above remains the actual root-cause fix; it is not done
  here and should be scoped as its own change if the team wants to close the pattern permanently
  rather than patch each symptom.
- **Client-side lag gauges vanish when the consumer process dies** — this fix only restores
  metrics while the consumer is alive and polling. It does **not** make `KafkaConsumerLagHigh`
  fire for a *fully down* notifications consumer (the gauge simply disappears with the process).
  That case must be covered by `up{job="eurotransit-notifications"} == 0` / scrape-target-down
  alerting, or, if lag visibility that survives consumer death is wanted, a broker-side exporter
  (`kafka-exporter`) — deliberately out of scope here, reserved to the team as a separate
  decision (see Alternatives).
- `KafkaConsumerMetricsIT` requires Docker (Testcontainers Postgres; embedded Kafka itself is
  in-process and Docker-free). It was written, compiles, and was run in the drafting environment
  — but failed to start there for a reason unrelated to this change: Testcontainers'
  `DockerClientProviderStrategy` gets a `BadRequestException (Status 400)` with an almost-empty
  `/info` payload (`Labels: ["com.docker.desktop.address=npipe://\\.\pipe\docker_cli"]`) from
  both the default `npipe:////./pipe/docker_engine` and an explicit
  `npipe:////./pipe/dockerDesktopLinuxEngine` `DOCKER_HOST` — i.e. this Windows Docker Desktop
  install fronts its engine pipes with a CLI-redirector stub that docker-java 3.4.0 cannot parse
  as a valid `/info` response. This is the Windows analogue of the macOS "Docker Desktop API
  proxy returns HTTP 400" issue this same `build.gradle.kts` already works around for
  Testcontainers itself (`extra["testcontainers.version"] = "1.20.4"` comment) — that workaround
  only special-cases the macOS raw-socket path, so it does not cover this. **This is a local
  Docker Desktop/testcontainers compatibility problem, not a defect in the fix** — confirmed
  separately by `KafkaConsumerFactoryMicrometerBindingTest`, which exercises the identical
  `KafkaConfig.consumerFactory` wiring without needing a broker at all and passes. Still, the
  end-to-end IT must be run somewhere with a working Docker/Testcontainers setup (CI, or a
  corrected local Docker Desktop config — e.g. a raw-socket workaround analogous to the existing
  macOS one, or the Windows equivalent) before this ADR is ratified.

### Reserved to the team (not decided here)

- Whether to pursue the broker-side `kafka-exporter` for consumer-death-proof lag visibility, and
  if so, its own config-repo panel/alert wiring.
- Whether the "stop replacing Boot's `ConsumerFactory`" refactor (Alternatives) is worth doing
  proactively, or only if a third autoconfigured behavior is found missing.

## References

- GitHub issue (eurotransit-app): "notifications-service exposes no Kafka consumer metrics — lag
  panel and KafkaConsumerLagHigh alert are blind to it"
- ADR 0003 — Notifications failure handling & offset-commit semantics (why the custom
  `ConsumerFactory`/DLT wiring exists in the first place)
- `backend/notifications-service/.../config/KafkaConfig.kt` — the changed bean
- `backend/notifications-service/.../config/KafkaConsumerFactoryMicrometerBindingTest.kt` — new
  Docker-free wiring test (executed, passing)
- `backend/notifications-service/.../config/KafkaConsumerMetricsIT.kt` — new end-to-end
  regression test (compiles; not yet executed — blocked locally by an unrelated Docker Desktop
  issue, see Consequences)
- `backend/orders-service/.../config/KafkaErrorHandlingConfig.kt` — the "customize only the error
  handler, keep Boot's factory" pattern referenced in Alternatives
- config repo: `deploy/charts/eurotransit/templates/orders/prometheusrule.yaml`
  (`KafkaConsumerLagHigh`, and the `adversarial-audit fix #52` comment on the prior mute-alert
  incident) and `deploy/charts/eurotransit/dashboards/red-money-path.json` ("Kafka pipeline —
  consumer records lag" panel)
