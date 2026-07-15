package com.eurotransit.notifications.config

import com.eurotransit.notifications.AbstractIntegrationTest
import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.time.Duration

/**
 * Regression test for the gap fixed by ADR 0008: the hand-built [KafkaConfig.consumerFactory]
 * must still end up wearing a Micrometer consumer listener, or `kafka_consumer_*` metrics
 * silently stop being published (as they did before this fix — see the linked GitHub issue).
 */
class KafkaConsumerMetricsIT(
    @Autowired val meterRegistry: MeterRegistry,
    @Autowired val brokers: EmbeddedKafkaBroker,
) : AbstractIntegrationTest() {

    @Test
    fun `consumer factory is bound to the Micrometer registry and publishes fetch-manager lag`() {
        val producer = KafkaTemplate(
            DefaultKafkaProducerFactory<String, Any>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
                )
            )
        )
        // The lag gauge is registered lazily, on first poll/assignment — a message has to
        // flow through the real listener container before the meter exists.
        producer.send("order-confirmed", "order-metrics-1", OrderConfirmedEvent("order-metrics-1", "carol@example.com", null))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val meter = meterRegistry.find("kafka.consumer.fetch.manager.records.lag").meter()
            assertTrue(meter != null, "expected a kafka.consumer.fetch.manager.records.lag meter to be registered")
        }
    }
}
