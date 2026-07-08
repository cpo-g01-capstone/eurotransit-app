package com.eurotransit.notifications.listener

import com.eurotransit.notifications.AbstractIntegrationTest
import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.persistence.SentNotificationRepository
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
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.time.Duration

/** Proves the handler is idempotent: a duplicated `order-confirmed` does not send twice. */
class RedeliveryIT(
    @Autowired val repository: SentNotificationRepository,
    @Autowired val registry: MeterRegistry,
    @Autowired val brokers: EmbeddedKafkaBroker,
) : AbstractIntegrationTest() {

    @Test
    fun `duplicate order-confirmed does not send twice`() {
        val id = "order-dup-1"
        val template = KafkaTemplate(
            DefaultKafkaProducerFactory<String, Any>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
                )
            )
        )
        val before = registry.counter("notifications_sent_total").count()

        val event = OrderConfirmedEvent(id, "carol@example.com", null)
        template.send("order-confirmed", id, event)
        template.send("order-confirmed", id, event) // duplicate delivery

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertEquals("SENT", runBlocking { repository.findStatus(id) })
        }
        // Give the duplicate time to be consumed, then assert exactly one send happened.
        Thread.sleep(2000)
        assertEquals(1.0, registry.counter("notifications_sent_total").count() - before)
    }
}
