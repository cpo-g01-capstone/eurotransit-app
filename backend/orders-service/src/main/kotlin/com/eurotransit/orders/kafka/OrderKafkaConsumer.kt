package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderConfirmedEvent
import com.eurotransit.orders.event.PaymentAuthorizedEvent
import com.eurotransit.orders.lifecycle.GracefulShutdownManager
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.model.ProcessedEvent
import com.eurotransit.orders.repository.OrderRepository
import com.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Consumes `payment-authorized` events and confirms orders idempotently.
 *
 * Dedup pattern (from docs/design/idempotency.md):
 * 1. Read-before-write check on processed_events
 * 2. Business logic + dedup insert in one transaction
 * 3. Downstream publish outside transaction (at-least-once safe)
 *
 * Graceful shutdown:
 * - If the service is draining, new events are skipped (no ack) so Kafka
 *   rebalances them to a healthy instance.
 * - In-flight operations are tracked so the shutdown sequence waits for them.
 * - ensureActive() provides a cooperative cancellation checkpoint before
 *   downstream publish.
 */
@Component
class OrderKafkaConsumer(
    private val orderRepository: OrderRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
    private val transactionalOperator: TransactionalOperator,
    private val shutdownManager: GracefulShutdownManager
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
        // Cooperative shutdown: skip processing, Kafka will rebalance to healthy instance
        if (!shutdownManager.isAcceptingTraffic()) {
            logger.info("Shutting down — not processing event for order {}", event.orderId)
            return // no ack → will be redelivered after rebalance
        }

        val eventId = "${event.orderId}:payment-authorized"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            ack.acknowledge()
            return
        }

        // 2-3. Track as in-flight so shutdown waits for completion
        shutdownManager.trackInflight {
            // 2. Business logic + dedup record in ONE transaction.
            //    The lambda returns whether the state transition actually applied.
            val confirmed = transactionalOperator.executeAndAwait {
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
                updated == 1
            }

            // Cooperative cancellation checkpoint before downstream publish
            coroutineContext.ensureActive()

            // 3. Publish downstream event (outside TX — at-least-once safe).
            //    ONLY when the transition applied: publishing order-confirmed for an
            //    order that was NOT confirmed would make Notifications send the
            //    customer a confirmation for an unconfirmed order.
            if (confirmed == true) {
                orderKafkaProducer.sendOrderConfirmed(
                    OrderConfirmedEvent(orderId = event.orderId)
                )
                logger.info("Order {} confirmed after payment authorization", event.orderId)
            }
        }

        ack.acknowledge()
    }
}
