package com.eurotransit.notifications.listener

import com.eurotransit.notifications.AbstractIntegrationTest
import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.persistence.SentNotificationRepository
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

class OrderConfirmedListenerIT(
    @Autowired val repository: SentNotificationRepository,
    @Autowired val brokers: EmbeddedKafkaBroker,
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
