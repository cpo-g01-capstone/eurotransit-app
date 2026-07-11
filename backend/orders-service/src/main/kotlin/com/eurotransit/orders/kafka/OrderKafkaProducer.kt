package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderConfirmedEvent
import com.eurotransit.orders.event.OrderFailedEvent
import com.eurotransit.orders.event.OrderPlacedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OrderKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun sendOrderPlaced(event: OrderPlacedEvent) {
        logger.info("Publishing order-placed for orderId={}", event.orderId)
        kafkaTemplate.send(TOPIC_ORDER_PLACED, event.orderId.toString(), event)
    }

    suspend fun sendOrderConfirmed(event: OrderConfirmedEvent) {
        logger.info("Publishing order-confirmed for orderId={}", event.orderId)
        kafkaTemplate.send(TOPIC_ORDER_CONFIRMED, event.orderId.toString(), event)
    }

    /**
     * Not suspend: called from the Kafka error handler's recoverer, which runs
     * on the (blocking) container thread. KafkaTemplate.send is async anyway.
     */
    fun sendOrderFailed(event: OrderFailedEvent) {
        logger.info("Publishing order-failed for orderId={} ({})", event.orderId, event.reason)
        kafkaTemplate.send(TOPIC_ORDER_FAILED, event.orderId.toString(), event)
    }

    companion object {
        const val TOPIC_ORDER_PLACED = "order-placed"
        const val TOPIC_ORDER_CONFIRMED = "order-confirmed"
        const val TOPIC_ORDER_FAILED = "order-failed"
    }
}
