# Notifications Consumer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the `notifications-service` Kafka consumer that sends order-confirmation notifications from `order-confirmed`, idempotently, without ever affecting checkout.

**Architecture:** A Spring Boot / Kotlin service consumes `order-confirmed`, deduplicates in a dedicated PostgreSQL table (`sent_notifications`) using a two-phase `PENDING → SENT` row, "sends" via a log-based stub, and routes exhausted send failures to `order-confirmed.DLT`. Offsets commit only after successful processing (`AckMode.RECORD`); a down dedup DB blocks-and-lags (infinite retry) instead of dropping. See `docs/adr/ADR-001..004` and `docs/superpowers/specs/2026-07-08-notifications-consumer-design.md`.

**Tech Stack:** Kotlin 1.9.24 (coroutines), Spring Boot 3.3.0, Spring Kafka, Spring Data R2DBC (`CoroutineCrudRepository`), Flyway, PostgreSQL, Micrometer, JUnit 5 + Testcontainers + `@EmbeddedKafka` + MockK.

## Global Constraints

- Package root: `com.eurotransit.notifications`; module `backend/notifications-service`.
- **Idempotency:** the `order-confirmed` handler must be safe under redelivery (composite key `orderId` + event type; since this consumer handles only `order-confirmed`, `order_id` alone is the PK).
- **Graceful degradation:** Notifications failures (DB, stub, Kafka) must NEVER propagate to checkout. There is no synchronous call back to Orders — do not introduce one.
- **Probes:** liveness `/actuator/health/liveness` = local process only; readiness `/actuator/health/readiness` = lifecycle only. Do NOT add `db`/`kafka` health indicators to the readiness group.
- **Structured concurrency:** one `CoroutineScope(SupervisorJob() + Dispatchers.IO)` as the failure domain; cancel on shutdown. No `GlobalScope`; no `runBlocking` outside bootstrap.
- **Team-owned knobs (do NOT invent values silently — copy these placeholders and flag for team):** retry attempts (`5`), backoff (`500ms × 2, max 10s`), DB-down retry interval (`5s`, unbounded), DLT partitions/retention. These are defaults proposed for review, not final.
- **Git:** per this repo's `AGENTS.md` the AI executor must NOT run `git commit`/`git push`. Commit steps below are performed by the human developer on a `feature/<id>-notifications-consumer` branch.
- Build with `./gradlew :backend:notifications-service:build`; test a single test with `./gradlew :backend:notifications-service:test --tests "<FQN>"`.

---

### Task 1: Module dependencies, config, schema, and integration test harness

**Files:**
- Modify: `backend/notifications-service/build.gradle.kts`
- Modify: `backend/notifications-service/src/main/resources/application.yml`
- Create: `backend/notifications-service/src/main/resources/db/migration/V1__init_notifications_schema.sql`
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/AbstractIntegrationTest.kt`
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/ContextLoadTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `AbstractIntegrationTest` base class — starts a `PostgreSQLContainer("postgres:16-alpine")`, an `@EmbeddedKafka` broker with topics `order-confirmed` and `order-confirmed.DLT`, and wires `spring.r2dbc.*`, `spring.flyway.*`, `spring.kafka.bootstrap-servers` via `@DynamicPropertySource`. Table `sent_notifications(order_id PK, status, created_at, updated_at)`.

- [ ] **Step 1: Add dependencies**

Replace the contents of `backend/notifications-service/build.gradle.kts` with:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.mockk:mockk:1.13.11")
}
```

- [ ] **Step 2: Configure application.yml**

Replace `backend/notifications-service/src/main/resources/application.yml` with:

```yaml
server:
  port: 8085
spring:
  application:
    name: notifications-service
  r2dbc:
    url: ${NOTIFICATIONS_DB_R2DBC_URL:r2dbc:postgresql://localhost:5432/notificationsdb}
    username: ${NOTIFICATIONS_DB_USERNAME:app}
    password: ${NOTIFICATIONS_DB_PASSWORD:app}
  flyway:
    enabled: true
    locations: classpath:db/migration
    # Flyway runs on JDBC (R2DBC is runtime-only). In Kubernetes set NOTIFICATIONS_DB_JDBC_URL
    # from the operator secret eurotransit-notifications-db-app (key jdbc-uri).
    url: ${NOTIFICATIONS_DB_JDBC_URL:jdbc:postgresql://localhost:5432/notificationsdb}
    user: ${NOTIFICATIONS_DB_USERNAME:app}
    password: ${NOTIFICATIONS_DB_PASSWORD:app}
    baseline-on-migrate: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:eurotransit-kafka-kafka-bootstrap.eurotransit:9092}
    consumer:
      group-id: notifications
      auto-offset-reset: earliest
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 3: Create the Flyway migration**

Create `backend/notifications-service/src/main/resources/db/migration/V1__init_notifications_schema.sql`:

```sql
-- Notifications service — initial schema.
-- Applied by Flyway on startup. Do not edit once merged; add V2__ for changes.
-- status uses VARCHAR + CHECK (not a PG ENUM) to avoid R2DBC enum-codec complexity.

CREATE TABLE sent_notifications (
    order_id    VARCHAR(255) PRIMARY KEY,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

- [ ] **Step 4: Create the integration-test base class**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/AbstractIntegrationTest.kt`:

```kotlin
package com.eurotransit.notifications

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = ["order-confirmed", "order-confirmed.DLT"])
abstract class AbstractIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgres.username }
            registry.add("spring.r2dbc.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
            registry.add("spring.kafka.bootstrap-servers") {
                System.getProperty("spring.embedded.kafka.brokers")
            }
        }
    }
}
```

- [ ] **Step 5: Write the failing context-load test**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/ContextLoadTest.kt`:

```kotlin
package com.eurotransit.notifications

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient

class ContextLoadTest(@Autowired val db: DatabaseClient) : AbstractIntegrationTest() {

    @Test
    fun `context loads and flyway created sent_notifications`() = runTest {
        val count = db.sql("SELECT count(*) AS c FROM sent_notifications")
            .map { row -> row.get("c", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()
        assertEquals(0L, count)
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.ContextLoadTest"`
Expected: FAIL (before Steps 1–4 are in place, compilation/context errors; after them it should pass — if it still fails, read the Flyway/R2DBC connection error and fix the property wiring).

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.ContextLoadTest"`
Expected: PASS. (Docker must be running for Testcontainers.)

- [ ] **Step 8: Commit (human)**

```bash
git add backend/notifications-service/build.gradle.kts \
        backend/notifications-service/src/main/resources/application.yml \
        backend/notifications-service/src/main/resources/db/migration/V1__init_notifications_schema.sql \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/AbstractIntegrationTest.kt \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/ContextLoadTest.kt
git commit -m "feat(notifications): add db/kafka config, dedup schema, test harness"
```

---

### Task 2: Deduplication persistence layer

**Files:**
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/persistence/SentNotification.kt`
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/persistence/SentNotificationRepository.kt`
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/persistence/SentNotificationRepositoryTest.kt`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (Task 1).
- Produces:
  - `SentNotificationRepository.claim(orderId: String): Long` — atomic insert-if-absent; returns `1` if newly claimed, `0` if a row already existed.
  - `SentNotificationRepository.findStatus(orderId: String): String?` — `"PENDING" | "SENT" | "FAILED"` or `null`.
  - `SentNotificationRepository.updateStatus(orderId: String, status: String): Long` — rows updated.

- [ ] **Step 1: Write the failing repository test**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/persistence/SentNotificationRepositoryTest.kt`:

```kotlin
package com.eurotransit.notifications.persistence

import com.eurotransit.notifications.AbstractIntegrationTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class SentNotificationRepositoryTest(
    @Autowired val repository: SentNotificationRepository,
) : AbstractIntegrationTest() {

    @Test
    fun `claim inserts once then reports conflict`() = runTest {
        val id = "order-claim-1"
        assertEquals(1L, repository.claim(id))   // newly claimed
        assertEquals(0L, repository.claim(id))   // already exists
        assertEquals("PENDING", repository.findStatus(id))
    }

    @Test
    fun `updateStatus transitions the row`() = runTest {
        val id = "order-claim-2"
        repository.claim(id)
        assertEquals(1L, repository.updateStatus(id, "SENT"))
        assertEquals("SENT", repository.findStatus(id))
    }

    @Test
    fun `findStatus is null for unknown order`() = runTest {
        assertNull(repository.findStatus("does-not-exist"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.persistence.SentNotificationRepositoryTest"`
Expected: FAIL — `SentNotificationRepository` does not exist (compilation error).

- [ ] **Step 3: Create the entity**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/persistence/SentNotification.kt`:

```kotlin
package com.eurotransit.notifications.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

@Table("sent_notifications")
data class SentNotification(
    @Id
    @Column("order_id")
    val orderId: String,
    val status: String,
    @Column("created_at") val createdAt: OffsetDateTime? = null,
    @Column("updated_at") val updatedAt: OffsetDateTime? = null,
)
```

- [ ] **Step 4: Create the repository**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/persistence/SentNotificationRepository.kt`:

```kotlin
package com.eurotransit.notifications.persistence

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SentNotificationRepository : CoroutineCrudRepository<SentNotification, String> {

    /** Atomic insert-if-absent. Returns 1 when newly claimed, 0 when a row already exists. */
    @Modifying
    @Query(
        """
        INSERT INTO sent_notifications (order_id, status)
        VALUES (:orderId, 'PENDING')
        ON CONFLICT (order_id) DO NOTHING
        """
    )
    suspend fun claim(orderId: String): Long

    @Query("SELECT status FROM sent_notifications WHERE order_id = :orderId")
    suspend fun findStatus(orderId: String): String?

    @Modifying
    @Query("UPDATE sent_notifications SET status = :status, updated_at = NOW() WHERE order_id = :orderId")
    suspend fun updateStatus(orderId: String, status: String): Long
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.persistence.SentNotificationRepositoryTest"`
Expected: PASS.

- [ ] **Step 6: Commit (human)**

```bash
git add backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/persistence/ \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/persistence/
git commit -m "feat(notifications): dedup repository with atomic claim + status transitions"
```

---

### Task 3: Order-confirmed event DTO and the stub email sender

**Files:**
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/OrderConfirmedEvent.kt`
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/EmailSender.kt`
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/LoggingEmailSender.kt`
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/email/LoggingEmailSenderTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `data class OrderConfirmedEvent(orderId: String, customerContact: String, confirmedAt: String?)`.
  - `interface EmailSender { suspend fun send(event: OrderConfirmedEvent) }`.
  - `LoggingEmailSender` — logs + increments counter `notifications_sent_total`.

- [ ] **Step 1: Write the failing test**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/email/LoggingEmailSenderTest.kt`:

```kotlin
package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LoggingEmailSenderTest {

    @Test
    fun `send increments the sent counter`() = runTest {
        val registry = SimpleMeterRegistry()
        val sender = LoggingEmailSender(registry)

        sender.send(OrderConfirmedEvent("order-1", "alice@example.com", null))

        assertEquals(1.0, registry.counter("notifications_sent_total").count())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.email.LoggingEmailSenderTest"`
Expected: FAIL — `OrderConfirmedEvent` / `LoggingEmailSender` do not exist.

- [ ] **Step 3: Create the event DTO**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/OrderConfirmedEvent.kt`:

```kotlin
package com.eurotransit.notifications

/**
 * Payload of the `order-confirmed` topic (ADR-001). The recipient snapshot travels in the
 * event so Notifications never calls back to Orders. Align field names with the Orders producer.
 */
data class OrderConfirmedEvent(
    val orderId: String,
    val customerContact: String,
    val confirmedAt: String? = null,
)
```

- [ ] **Step 4: Create the EmailSender interface and stub**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/EmailSender.kt`:

```kotlin
package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent

interface EmailSender {
    /** Sends the confirmation. May throw; the caller treats a throw as a send failure. */
    suspend fun send(event: OrderConfirmedEvent)
}
```

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/LoggingEmailSender.kt`:

```kotlin
package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Stub sender (ADR/spec): logs and counts. No real SMTP — the project grades resilience. */
@Component
class LoggingEmailSender(registry: MeterRegistry) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sent = registry.counter("notifications_sent_total")

    override suspend fun send(event: OrderConfirmedEvent) {
        log.info("Sending order-confirmation for order={} to={}", event.orderId, event.customerContact)
        sent.increment()
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.email.LoggingEmailSenderTest"`
Expected: PASS.

- [ ] **Step 6: Commit (human)**

```bash
git add backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/OrderConfirmedEvent.kt \
        backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/email/ \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/email/
git commit -m "feat(notifications): order-confirmed DTO and stub email sender with metrics"
```

---

### Task 4: NotificationService — dedup + two-phase orchestration

**Files:**
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/service/NotificationService.kt`
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/service/NotificationServiceTest.kt`

**Interfaces:**
- Consumes: `SentNotificationRepository` (Task 2), `EmailSender` (Task 3), `OrderConfirmedEvent` (Task 3).
- Produces: `NotificationService.handle(event: OrderConfirmedEvent)` — idempotent; sends at most once per order except the documented crash-after-send window; throws on send failure so the Kafka error handler can retry/DLT.

- [ ] **Step 1: Write the failing unit tests**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/service/NotificationServiceTest.kt`:

```kotlin
package com.eurotransit.notifications.service

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.email.EmailSender
import com.eurotransit.notifications.persistence.SentNotificationRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NotificationServiceTest {

    private val repository = mockk<SentNotificationRepository>()
    private val emailSender = mockk<EmailSender>(relaxed = true)
    private val service = NotificationService(repository, emailSender)
    private val event = OrderConfirmedEvent("order-1", "alice@example.com", null)

    @Test
    fun `fresh order is sent then marked SENT`() = runTest {
        coEvery { repository.claim("order-1") } returns 1L
        coEvery { repository.updateStatus("order-1", "SENT") } returns 1L

        service.handle(event)

        coVerify(exactly = 1) { emailSender.send(event) }
        coVerify(exactly = 1) { repository.updateStatus("order-1", "SENT") }
    }

    @Test
    fun `already SENT order is skipped`() = runTest {
        coEvery { repository.claim("order-1") } returns 0L
        coEvery { repository.findStatus("order-1") } returns "SENT"

        service.handle(event)

        coVerify(exactly = 0) { emailSender.send(any()) }
    }

    @Test
    fun `PENDING order from a prior crash is retried`() = runTest {
        coEvery { repository.claim("order-1") } returns 0L
        coEvery { repository.findStatus("order-1") } returns "PENDING"
        coEvery { repository.updateStatus("order-1", "SENT") } returns 1L

        service.handle(event)

        coVerify(exactly = 1) { emailSender.send(event) }
    }

    @Test
    fun `send failure propagates and does not mark SENT`() = runTest {
        coEvery { repository.claim("order-1") } returns 1L
        coEvery { emailSender.send(event) } throws RuntimeException("smtp down")

        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { service.handle(event) }
        }
        coVerify(exactly = 0) { repository.updateStatus("order-1", "SENT") }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.service.NotificationServiceTest"`
Expected: FAIL — `NotificationService` does not exist.

- [ ] **Step 3: Implement NotificationService**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/service/NotificationService.kt`:

```kotlin
package com.eurotransit.notifications.service

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.email.EmailSender
import com.eurotransit.notifications.persistence.SentNotificationRepository
import org.springframework.stereotype.Service

/**
 * Idempotent handling of `order-confirmed` (ADR-002/003):
 * claim (insert-if-absent PENDING) -> send -> mark SENT. Redelivery of a SENT/FAILED order is a
 * no-op; a PENDING order (prior incomplete attempt) is retried. A send failure is rethrown so the
 * Kafka error handler can retry and eventually route to the DLT.
 */
@Service
class NotificationService(
    private val repository: SentNotificationRepository,
    private val emailSender: EmailSender,
) {
    suspend fun handle(event: OrderConfirmedEvent) {
        val claimed = repository.claim(event.orderId)
        if (claimed == 0L) {
            when (repository.findStatus(event.orderId)) {
                "SENT", "FAILED" -> return          // dedup hit / terminal — nothing to do
                else -> Unit                        // PENDING — retry the send below
            }
        }
        emailSender.send(event)                     // may throw -> error handler retries -> DLT
        repository.updateStatus(event.orderId, "SENT")
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.service.NotificationServiceTest"`
Expected: PASS (all four).

- [ ] **Step 5: Commit (human)**

```bash
git add backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/service/ \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/service/
git commit -m "feat(notifications): idempotent two-phase notification orchestration"
```

---

### Task 5: Kafka wiring — listener, manual-ack container, error handler + DLT, lifecycle scope

**Files:**
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/lifecycle/NotificationsLifecycle.kt`
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/config/KafkaConfig.kt`
- Create: `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/listener/OrderConfirmedListener.kt`
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/listener/OrderConfirmedListenerIT.kt`

**Interfaces:**
- Consumes: `NotificationService.handle` (Task 4), `SentNotificationRepository` (Task 2), `OrderConfirmedEvent` (Task 3), `AbstractIntegrationTest` (Task 1).
- Produces: the running consumer. Container factory `kafkaListenerContainerFactory` with `AckMode.RECORD`; `DefaultErrorHandler` that routes exhausted send failures to `order-confirmed.DLT` and retries transient DB errors indefinitely (block-and-lag); `serviceScope` bean (the failure domain).

- [ ] **Step 1: Create the lifecycle scope (failure domain)**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/lifecycle/NotificationsLifecycle.kt`:

```kotlin
package com.eurotransit.notifications.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** One CoroutineScope per failure domain (CLAUDE.md). Cancelled on SIGTERM/app shutdown. */
@Configuration
class NotificationsLifecycle : DisposableBean {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Bean
    fun serviceScope(): CoroutineScope = scope

    override fun destroy() {
        scope.cancel()
    }
}
```

- [ ] **Step 2: Create the Kafka configuration**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/config/KafkaConfig.kt`:

```kotlin
package com.eurotransit.notifications.config

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.persistence.SentNotificationRepository
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries
import org.springframework.util.backoff.FixedBackOff

@Configuration
@EnableKafka
class KafkaConfig {

    @Bean
    fun consumerFactory(props: KafkaProperties): ConsumerFactory<String, OrderConfirmedEvent> {
        val config = props.buildConsumerProperties(null).toMutableMap()
        config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        val json = JsonDeserializer(OrderConfirmedEvent::class.java).apply {
            setUseTypeHeaders(false)
            addTrustedPackages("com.eurotransit.notifications")
        }
        return DefaultKafkaConsumerFactory(
            config,
            StringDeserializer(),
            ErrorHandlingDeserializer(json),
        )
    }

    /** Producer used only by the DLT recoverer. */
    @Bean
    fun dltKafkaTemplate(props: KafkaProperties): KafkaTemplate<String, Any> {
        val config = props.buildProducerProperties(null).toMutableMap()
        config[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        config[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JsonSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(config))
    }

    @Bean
    fun errorHandler(
        dltKafkaTemplate: KafkaTemplate<String, Any>,
        serviceScope: CoroutineScope,
        repository: SentNotificationRepository,
    ): DefaultErrorHandler {
        val publisher = DeadLetterPublishingRecoverer(dltKafkaTemplate) { record, _ ->
            TopicPartition("${record.topic()}.DLT", record.partition())
        }
        // Mark the row FAILED (fire-and-forget on the service scope — no runBlocking), then DLT.
        val recoverer = org.springframework.kafka.listener.ConsumerRecordRecoverer { record, ex ->
            (record.value() as? OrderConfirmedEvent)?.let { event ->
                serviceScope.launch { repository.updateStatus(event.orderId, "FAILED") }
            }
            publisher.accept(record, ex)
        }
        // Team-owned knobs: 5 bounded retries for send failures.
        val bounded = ExponentialBackOffWithMaxRetries(5).apply {
            initialInterval = 500L
            multiplier = 2.0
            maxInterval = 10_000L
        }
        // Transient DB errors: retry forever (block-and-lag) rather than DLT/drop.
        val blockAndLag = FixedBackOff(5_000L, Long.MAX_VALUE)
        return DefaultErrorHandler(recoverer, bounded).apply {
            setBackOffFunction { _, ex -> if (isTransientDataAccess(ex)) blockAndLag else bounded }
        }
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, OrderConfirmedEvent>,
        errorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, OrderConfirmedEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, OrderConfirmedEvent>()
        factory.consumerFactory = consumerFactory
        factory.setCommonErrorHandler(errorHandler)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        factory.containerProperties.isStopImmediate = false  // graceful drain of the in-flight record
        return factory
    }

    private fun isTransientDataAccess(ex: Throwable?): Boolean {
        var e: Throwable? = ex
        while (e != null) {
            if (e is DataAccessResourceFailureException || e is R2dbcException) return true
            e = e.cause
        }
        return false
    }
}
```

- [ ] **Step 3: Create the listener**

Create `backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/listener/OrderConfirmedListener.kt`:

```kotlin
package com.eurotransit.notifications.listener

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.service.NotificationService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Terminal consumer of the money path (ADR-001). Suspend handler; the container commits the
 * offset only after this returns normally (AckMode.RECORD). A throw is handled by the
 * DefaultErrorHandler (retry -> DLT for send failures; block-and-lag for transient DB errors).
 */
@Component
class OrderConfirmedListener(private val service: NotificationService) {

    @KafkaListener(
        topics = ["order-confirmed"],
        containerFactory = "kafkaListenerContainerFactory",
    )
    suspend fun onOrderConfirmed(event: OrderConfirmedEvent) {
        service.handle(event)
    }
}
```

- [ ] **Step 4: Write the failing integration test**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/listener/OrderConfirmedListenerIT.kt`:

```kotlin
package com.eurotransit.notifications.listener

import com.eurotransit.notifications.AbstractIntegrationTest
import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.email.EmailSender
import com.eurotransit.notifications.persistence.SentNotificationRepository
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.test.context.TestPropertySource
import java.time.Duration

@TestPropertySource(properties = ["notifications.test.fail-send=\${fail-send:false}"])
class OrderConfirmedListenerIT(
    @Autowired val repository: SentNotificationRepository,
    @Autowired val brokers: org.springframework.kafka.test.EmbeddedKafkaBroker,
) : AbstractIntegrationTest() {

    private fun template(): KafkaTemplate<String, Any> {
        val props = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }

    @Test
    fun `order-confirmed is processed and marked SENT`() {
        val id = "order-happy-1"
        template().send("order-confirmed", id, OrderConfirmedEvent(id, "alice@example.com", null))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertEquals("SENT", runBlocking { repository.findStatus(id) })
        }
    }
}
```

- [ ] **Step 5: Run the integration test to verify it fails then passes**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.listener.OrderConfirmedListenerIT"`
Expected: FAIL before Steps 1–3 (no listener); PASS after. If it hangs, check that `@EmbeddedKafka` topics include `order-confirmed` and the container factory bean name matches `@KafkaListener(containerFactory=...)`.

- [ ] **Step 6: Add the DLT integration test**

Append to `OrderConfirmedListenerIT.kt` (inside the class), plus the failing-sender test configuration:

```kotlin
    @Test
    fun `exhausted send failure lands in order-confirmed_DLT and row is FAILED`() {
        val id = "order-fail-1"
        // Consumer to read the DLT.
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
            ConsumerConfig.GROUP_ID_CONFIG to "dlt-test",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        )
        val dltConsumer = DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer()
        dltConsumer.subscribe(listOf("order-confirmed.DLT"))

        template().send("order-confirmed", id, OrderConfirmedEvent(id, "bob@example.com", null))

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val records = dltConsumer.poll(Duration.ofMillis(500))
            assertTrue(records.count() >= 1)
        }
        dltConsumer.close()
        assertEquals("FAILED", runBlocking { repository.findStatus(id) })
    }

    @TestConfiguration
    class FailingSenderConfig {
        @Bean
        @Primary
        fun failingEmailSender(): EmailSender = object : EmailSender {
            override suspend fun send(event: OrderConfirmedEvent) = throw RuntimeException("stub failure")
        }
    }
```

Then annotate the test class to import the failing sender **only for this scenario**. Simplest reliable approach: split the DLT test into its own file `OrderConfirmedDltIT.kt` with `@Import(OrderConfirmedDltIT.FailingSenderConfig::class)` on the class, so the happy-path test keeps the real `LoggingEmailSender`. Move the `FailingSenderConfig` and the DLT `@Test` into that new file (same package, extending `AbstractIntegrationTest`, reusing the `template()` helper). Keep bounded retries low for test speed by overriding in that file's `@TestPropertySource` if needed.

- [ ] **Step 7: Run the full module test suite**

Run: `./gradlew :backend:notifications-service:test`
Expected: PASS (context, repository, sender, service, happy-path IT, DLT IT).

- [ ] **Step 8: Commit (human)**

```bash
git add backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/lifecycle/ \
        backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/config/ \
        backend/notifications-service/src/main/kotlin/com/eurotransit/notifications/listener/ \
        backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/listener/
git commit -m "feat(notifications): kafka listener, manual-ack container, DLT + block-and-lag error handling"
```

---

### Task 6: Verify graceful degradation & idempotency end-to-end; update docs

**Files:**
- Create: `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/listener/RedeliveryIT.kt`
- Modify: `README.md` (notifications section, how to run tests)

**Interfaces:**
- Consumes: everything above.
- Produces: proof (test) that redelivery does not double-mark; documentation.

- [ ] **Step 1: Write the redelivery idempotency test**

Create `backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/listener/RedeliveryIT.kt`:

```kotlin
package com.eurotransit.notifications.listener

import com.eurotransit.notifications.AbstractIntegrationTest
import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import com.eurotransit.notifications.persistence.SentNotificationRepository
import java.time.Duration

class RedeliveryIT(
    @Autowired val repository: SentNotificationRepository,
    @Autowired val registry: MeterRegistry,
    @Autowired val brokers: org.springframework.kafka.test.EmbeddedKafkaBroker,
) : AbstractIntegrationTest() {

    @Test
    fun `duplicate order-confirmed does not send twice`() {
        val id = "order-dup-1"
        val props = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        val template = KafkaTemplate(DefaultKafkaProducerFactory<String, Any>(props))
        val before = registry.counter("notifications_sent_total").count()

        val event = OrderConfirmedEvent(id, "carol@example.com", null)
        template.send("order-confirmed", id, event)
        template.send("order-confirmed", id, event)   // duplicate delivery

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertEquals("SENT", runBlocking { repository.findStatus(id) })
        }
        // Allow the duplicate to be consumed, then assert exactly one send happened.
        Thread.sleep(2000)
        assertEquals(1.0, registry.counter("notifications_sent_total").count() - before)
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :backend:notifications-service:test --tests "com.eurotransit.notifications.listener.RedeliveryIT"`
Expected: PASS — the second delivery is a dedup hit; counter increments by exactly 1.

- [ ] **Step 3: Document in README**

Add a "Notifications service" subsection to `README.md` describing: consumes `order-confirmed`; dedicated PostgreSQL dedup (`sent_notifications`); at-least-once with `order-confirmed.DLT`; graceful degradation (checkout unaffected by Notifications outage); how to run `./gradlew :backend:notifications-service:test` (requires Docker for Testcontainers). Reference ADR-001..004.

- [ ] **Step 4: Run the whole module build**

Run: `./gradlew :backend:notifications-service:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (human)**

```bash
git add backend/notifications-service/src/test/kotlin/com/eurotransit/notifications/listener/RedeliveryIT.kt README.md
git commit -m "test(notifications): prove idempotent redelivery; document service"
```

---

## Self-Review

**Spec coverage:**
- §3 components → Tasks 2–5 (repository, sender, service, kafka config, listener, lifecycle). ✅
- §4 data flow (happy path, redelivery, send failure→DLT, DB down→block-and-lag) → Task 4 (logic), Task 5 (DLT + backoff classification), Task 6 (redelivery proof). ✅
- §5 lifecycle & probes → Task 1 (probes already enabled in application.yml, defaults keep db/kafka out of readiness), Task 5 (serviceScope failure domain + graceful container stop). ✅
- §6 build/schema → Task 1. ✅
- §7 observability (`notifications_sent_total`) → Task 3; DLT/lag metrics beyond the counter are config-repo/team follow-ups (§9/§10), intentionally out of this plan. ✅
- §8 testing → Tasks 2–6. The "SIGTERM drain" demonstration (§8) is exercised operationally in-cluster; here `isStopImmediate=false` + the serviceScope are wired, but a unit test for drain is not included (hard to assert deterministically in-process) — flagged for the config-repo chaos experiment instead.
- §9 config-repo follow-ups → out of this repo by design (twin PR). ✅

**Placeholder scan:** No TBD/TODO in code. Team-owned numeric knobs (retries/backoff) are set to concrete proposed defaults and flagged in Global Constraints. ✅

**Type consistency:** `claim/findStatus/updateStatus` signatures match across Tasks 2/4/5; `EmailSender.send(OrderConfirmedEvent)` consistent Tasks 3/4/5; `serviceScope` bean produced in Task 5 Step 1 and consumed in Task 5 Step 2. ✅

**Known implementation risk to verify during execution:** suspend `@KafkaListener` support and `@Modifying`-returning-`Long` on `CoroutineCrudRepository` are Spring-version-dependent. If the suspend listener is not picked up, fall back to a non-suspend listener that delegates via `serviceScope` and a `CompletableDeferred` (do not use `runBlocking`). Verify early in Task 5 Step 5.
