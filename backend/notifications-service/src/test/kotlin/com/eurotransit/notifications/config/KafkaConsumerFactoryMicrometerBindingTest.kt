package com.eurotransit.notifications.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.metrics.KafkaMetricsAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.MicrometerConsumerListener

/**
 * Docker-free regression test for ADR 0008: uses Spring Boot's REAL
 * [KafkaMetricsAutoConfiguration] (not a hand-copied stand-in) to prove
 * [KafkaConfig.consumerFactory] actually receives and applies the customizer Boot registers for
 * Micrometer — the exact wiring the linked GitHub issue found missing. No broker, no
 * Testcontainers, no full application context.
 */
class KafkaConsumerFactoryMicrometerBindingTest {

    @Configuration
    class MeterRegistryConfig {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Test
    fun `hand-built consumer factory carries a MicrometerConsumerListener after Boot's customizer is applied`() {
        ApplicationContextRunner()
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .withConfiguration(AutoConfigurations.of(KafkaMetricsAutoConfiguration::class.java))
            .run { context ->
                val customizers = context.getBeanProvider(DefaultKafkaConsumerFactoryCustomizer::class.java)
                assertTrue(
                    customizers.stream().count() > 0,
                    "expected Boot's KafkaMetricsAutoConfiguration to register a " +
                        "DefaultKafkaConsumerFactoryCustomizer on this classpath",
                )

                val factory = KafkaConfig().consumerFactory(KafkaProperties(), customizers)

                val hasMicrometerListener = (factory as DefaultKafkaConsumerFactory<*, *>)
                    .listeners
                    .any { it is MicrometerConsumerListener<*, *> }
                assertTrue(
                    hasMicrometerListener,
                    "expected a MicrometerConsumerListener on the manually built ConsumerFactory " +
                        "after customizers were applied — this is the metric binding the GitHub " +
                        "issue found missing",
                )
            }
    }
}
