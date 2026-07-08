package com.eurotransit.notifications

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = ["order-confirmed", "order-confirmed.DLT"])
abstract class AbstractIntegrationTest {
    companion object {
        // Singleton container: started once per JVM and shared across all test classes. NOT managed
        // by @Testcontainers/@Container, because Spring caches one context across classes while
        // per-class containers get stopped between classes — leaving the cached context pointing at a
        // dead database. Stopped on JVM shutdown.
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        init {
            postgres.start()
            Runtime.getRuntime().addShutdownHook(Thread { postgres.stop() })
        }

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
