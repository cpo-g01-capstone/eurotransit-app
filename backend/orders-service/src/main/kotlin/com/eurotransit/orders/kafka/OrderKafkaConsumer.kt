package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderConfirmedEvent
import com.eurotransit.orders.event.PaymentAuthorizedEvent
import com.eurotransit.orders.lifecycle.GracefulShutdownManager
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.model.ProcessedEvent
import com.eurotransit.orders.repository.OrderRepository
import com.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
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
 *
 * NOT a `suspend` @KafkaListener (adversarial-audit fix, #19): a suspend listener on this
 * Spring Kafka version swallows handler exceptions, so a DB failure here would
 * never reach the DefaultErrorHandler — no redelivery, no recoverer. Non-suspend
 * + runBlocking bridge, the team-ratified bridge pattern (ADR 0004, agent-log case 12).
 */
@Component
class OrderKafkaConsumer(
    private val orderRepository: OrderRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val entityTemplate: R2dbcEntityTemplate,
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
    fun handlePaymentAuthorized(record: ConsumerRecord<String, PaymentAuthorizedEvent?>, ack: Acknowledgment) {
        val event = record.value() ?: run {
            // Poison/undeserializable payload: ack and skip (ErrorHandlingDeserializer yields null).
            ack.acknowledge()
            return
        }

        // Cooperative shutdown: skip processing, Kafka will rebalance to healthy instance
        if (!shutdownManager.isAcceptingTraffic()) {
            logger.info("Shutting down — not processing event for order {}", event.orderId)
            return // no ack → will be redelivered after rebalance
        }

        runBlocking { handle(event) } // bridge: exceptions must reach the error handler (ADR 0004)
        ack.acknowledge()
    }

    private suspend fun handle(event: PaymentAuthorizedEvent) {
        val eventId = "${event.orderId}:payment-authorized"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
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

                // insert(), not save(): app-assigned @Id would map to UPDATE (agent-log case 17)
                entityTemplate.insert(ProcessedEvent(eventId, Instant.now())).awaitSingle()
                updated == 1
            }

            // Cooperative cancellation checkpoint before downstream publish
            coroutineContext.ensureActive()

            // 3. Publish downstream event (outside TX — at-least-once safe).
            //    ONLY when the transition applied: publishing order-confirmed for an
            //    order that was NOT confirmed would make Notifications send the
            //    customer a confirmation for an unconfirmed order.
            if (confirmed == true) {
                // Re-read the order to snapshot the recipient into the event (the
                // confirming update is count-based and does not return the row).
                val contact = orderRepository.findById(event.orderId)?.customerContact
                orderKafkaProducer.sendOrderConfirmed(
                    OrderConfirmedEvent(orderId = event.orderId, customerContact = contact)
                )
                logger.info("Order {} confirmed after payment authorization", event.orderId)
            }
        }
    }
}
