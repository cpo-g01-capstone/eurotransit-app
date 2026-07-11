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
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.time.Duration

/** Verifies exhausted send failures land in `order-confirmed.DLT` and the row is marked FAILED. */
@Import(OrderConfirmedDltIT.FailingSenderConfig::class)
class OrderConfirmedDltIT(
    @Autowired val repository: SentNotificationRepository,
    @Autowired val brokers: EmbeddedKafkaBroker,
) : AbstractIntegrationTest() {

    @TestConfiguration
    class FailingSenderConfig {
        @Bean
        @Primary
        fun failingEmailSender(): EmailSender = object : EmailSender {
            override suspend fun send(event: OrderConfirmedEvent): Unit = throw RuntimeException("stub failure")
        }
    }

    @Test
    fun `exhausted send failure lands in DLT and row is FAILED`() {
        val id = "order-fail-1"
        val producer = KafkaTemplate(
            DefaultKafkaProducerFactory<String, Any>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
                )
            )
        )
        val dltConsumer = DefaultKafkaConsumerFactory<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers.brokersAsString,
                ConsumerConfig.GROUP_ID_CONFIG to "dlt-test",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            )
        ).createConsumer()
        dltConsumer.subscribe(listOf("order-confirmed.DLT"))

        producer.send("order-confirmed", id, OrderConfirmedEvent(id, "bob@example.com", null))

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertTrue(dltConsumer.poll(Duration.ofMillis(500)).count() >= 1)
        }
        dltConsumer.close()
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertEquals("FAILED", runBlocking { repository.findStatus(id) })
        }
    }
}
