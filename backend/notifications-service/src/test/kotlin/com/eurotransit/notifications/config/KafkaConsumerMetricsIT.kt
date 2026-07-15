package com.eurotransit.notifications.config

import com.eurotransit.notifications.AbstractIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * Regression test for the gap fixed by ADR 0008: the hand-built [KafkaConfig.consumerFactory]
 * must still end up wearing a Micrometer consumer listener, or `kafka_consumer_*` metrics
 * silently stop being published (as they did before this fix — see the linked GitHub issue).
 *
 * This asserts on the meters Micrometer binds when the consumer is created. Per-partition
 * meters (`kafka.consumer.fetch.manager.records.lag` and friends) only exist on the Kafka
 * client after an assignment, and `KafkaMetrics` re-scans the client for new metrics on a
 * fixed 60-second schedule that it exposes no knob to shorten — so they cannot be awaited
 * within a sane test timeout. The binding is what the fix is about, and it is what this
 * asserts; [KafkaConsumerFactoryMicrometerBindingTest] pins the wiring itself.
 */
class KafkaConsumerMetricsIT(
    @Autowired val meterRegistry: MeterRegistry,
) : AbstractIntegrationTest() {

    @Test
    fun `consumer factory is bound to the Micrometer registry and publishes consumer metrics`() {
        // The listener container creates its consumer during startup, which is what triggers the
        // MicrometerConsumerListener — await rather than assert outright, since startup is async.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val consumerMeters = meterRegistry.meters.filter { it.id.name.startsWith("kafka.consumer.") }
            assertTrue(
                consumerMeters.isNotEmpty(),
                "expected kafka.consumer.* meters to be registered — the consumer factory is not " +
                    "bound to the Micrometer registry",
            )
        }
    }
}
