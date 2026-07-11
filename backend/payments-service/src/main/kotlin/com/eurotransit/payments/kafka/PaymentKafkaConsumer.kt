package com.eurotransit.payments.kafka

import com.eurotransit.payments.event.InventoryReservedEvent
import com.eurotransit.payments.event.PaymentAuthorizedEvent
import com.eurotransit.payments.lifecycle.GracefulShutdownManager
import com.eurotransit.payments.model.ProcessedEvent
import com.eurotransit.payments.repository.ProcessedEventRepository
import com.eurotransit.payments.service.PaymentService
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
 * Consumes `inventory-reserved` events and authorizes payments idempotently.
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
class PaymentKafkaConsumer(
    private val paymentService: PaymentService,
    private val processedEventRepository: ProcessedEventRepository,
    private val paymentKafkaProducer: PaymentKafkaProducer,
    private val transactionalOperator: TransactionalOperator,
    private val shutdownManager: GracefulShutdownManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["inventory-reserved"],
        groupId = "payments-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.payments.event.InventoryReservedEvent"
        ]
    )
    suspend fun handleInventoryReserved(event: InventoryReservedEvent, ack: Acknowledgment) {
        // Cooperative shutdown: skip processing, Kafka will rebalance to healthy instance
        if (!shutdownManager.isAcceptingTraffic()) {
            logger.info("Shutting down — not processing event for order {}", event.orderId)
            return // no ack → will be redelivered after rebalance
        }

        val eventId = "${event.orderId}:inventory-reserved"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            ack.acknowledge()
            return
        }

        // 2-3. Track as in-flight so shutdown waits for completion
        shutdownManager.trackInflight {
            // 2. Business logic + dedup record in ONE transaction
            val intent = transactionalOperator.executeAndAwait {
                val result = paymentService.authorizePayment(
                    orderId = event.orderId,
                    amount = event.amount
                )
                processedEventRepository.save(ProcessedEvent(eventId, Instant.now()))
                result
            }

            // Cooperative cancellation checkpoint before downstream publish
            coroutineContext.ensureActive()

            // 3. Publish downstream event (outside TX — at-least-once safe)
            paymentKafkaProducer.sendPaymentAuthorized(
                PaymentAuthorizedEvent(
                    orderId = event.orderId,
                    paymentId = intent.id,
                    amount = intent.amount
                )
            )
        }

        ack.acknowledge()
        logger.info("Payment authorized for orderId={}", event.orderId)
    }
}
