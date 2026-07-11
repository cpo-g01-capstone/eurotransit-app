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
    // OTLP exporter: ships spans to Tempo (T3 / config-repo ADR 0022). Version
    // managed by the Spring Boot BOM alongside the micrometer OTel bridge.
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

// Override Spring Boot 3.3's managed Testcontainers 1.19.8 (docker-java 3.3.6 returns HTTP 400
// against newer Docker Desktop's API proxy). Same override as notifications-service.
extra["testcontainers.version"] = "1.20.4"

// Forward local Docker/Testcontainers env vars to the forked test workers (see the
// identical block in notifications-service for the full rationale). No-op on CI.
tasks.withType<Test> {
    listOf("DOCKER_HOST", "DOCKER_API_VERSION", "TESTCONTAINERS_RYUK_DISABLED").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    val dockerDesktopRawSock =
        file("${System.getProperty("user.home")}/Library/Containers/com.docker.docker/Data/docker.raw.sock")
    if (dockerDesktopRawSock.exists() && System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix://${dockerDesktopRawSock.absolutePath}")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        systemProperty("api.version", "1.51")
    }
}
