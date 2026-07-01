package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderConfirmedEvent
import com.eurotransit.orders.event.PaymentAuthorizedEvent
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.model.ProcessedEvent
import com.eurotransit.orders.repository.OrderRepository
import com.eurotransit.orders.repository.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/**
 * Consumes `payment-authorized` events and confirms orders idempotently.
 *
 * Dedup pattern (from docs/design/idempotency.md):
 * 1. Read-before-write check on processed_events
 * 2. Business logic + dedup insert in one transaction
 * 3. Downstream publish outside transaction (at-least-once safe)
 */
@Component
class OrderKafkaConsumer(
    private val orderRepository: OrderRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
    private val transactionalOperator: TransactionalOperator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["payment-authorized"],
        groupId = "orders-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.orders.event.PaymentAuthorizedEvent"
        ]
    )
    suspend fun handlePaymentAuthorized(event: PaymentAuthorizedEvent, ack: Acknowledgment) {
        val eventId = "${event.orderId}:payment-authorized"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            ack.acknowledge()
            return
        }

        // 2. Business logic + dedup record in ONE transaction
        transactionalOperator.executeAndAwait {
            val updated = orderRepository.updateStatus(
                id = event.orderId,
                newStatus = OrderStatus.CONFIRMED,
                expectedStatus = OrderStatus.RESERVED
            )

            if (updated == 0) {
                logger.warn(
                    "Order {} could not be confirmed — not in RESERVED status or not found",
                    event.orderId
                )
                // Still insert dedup record to prevent retries from attempting again
            }

            processedEventRepository.save(ProcessedEvent(eventId, Instant.now()))
        }

        // 3. Publish downstream event (outside TX — at-least-once safe)
        orderKafkaProducer.sendOrderConfirmed(
            OrderConfirmedEvent(orderId = event.orderId)
        )

        ack.acknowledge()
        logger.info("Order {} confirmed after payment authorization", event.orderId)
    }
}
