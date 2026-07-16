// Override Spring Boot 3.3's managed Testcontainers 1.19.8 (docker-java 3.3.6 returns HTTP 400
// against newer Docker Desktop's API proxy). This property is read by the Spring dependency-
// management plugin and wins over the BOM. 1.20.4 ships docker-java 3.4.x.
extra["testcontainers.version"] = "1.20.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OTLP exporter: ships spans to Tempo (T3 / config-repo ADR 0022). Version
    // managed by the Spring Boot BOM alongside the micrometer OTel bridge.
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.mockk:mockk:1.13.11")
}

// Forward local Docker/Testcontainers env vars to the forked test workers. Gradle does not
// always propagate them, and some Docker Desktop setups need DOCKER_API_VERSION pinned
// (its engine caps the API version the client may negotiate). No-op on CI, where these are unset.
tasks.withType<Test> {
    // Let explicit Docker env from the shell win when set (e.g. remote engines).
    listOf("DOCKER_HOST", "DOCKER_API_VERSION", "TESTCONTAINERS_RYUK_DISABLED").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    // Docker Desktop for Mac routes the default socket through an API proxy that (a) makes
    // docker-java negotiate an API version the engine rejects with HTTP 400, and (b) breaks Ryuk.
    // When that environment is detected (its raw engine socket exists) and the user hasn't already
    // set DOCKER_HOST, talk to the raw socket, pin the API version, and disable Ryuk. Guarded so
    // CI and Linux runners (no such socket) are completely unaffected.
    val dockerDesktopRawSock =
        file("${System.getProperty("user.home")}/Library/Containers/com.docker.docker/Data/docker.raw.sock")
    if (dockerDesktopRawSock.exists() && System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix://${dockerDesktopRawSock.absolutePath}")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        systemProperty("api.version", "1.51")
    }
}
