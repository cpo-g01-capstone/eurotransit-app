package com.eurotransit.payments.kafka

import com.eurotransit.payments.event.InventoryReservedEvent
import com.eurotransit.payments.event.PaymentAuthorizedEvent
import com.eurotransit.payments.model.ProcessedEvent
import com.eurotransit.payments.repository.ProcessedEventRepository
import com.eurotransit.payments.service.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/**
 * Consumes `inventory-reserved` events and authorizes payments idempotently.
 *
 * Dedup pattern (from docs/design/idempotency.md):
 * 1. Read-before-write check on processed_events
 * 2. Business logic + dedup insert in one transaction
 * 3. Downstream publish outside transaction (at-least-once safe)
 */
@Component
class PaymentKafkaConsumer(
    private val paymentService: PaymentService,
    private val processedEventRepository: ProcessedEventRepository,
    private val paymentKafkaProducer: PaymentKafkaProducer,
    private val transactionalOperator: TransactionalOperator
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
        val eventId = "${event.orderId}:inventory-reserved"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            ack.acknowledge()
            return
        }

        // 2. Business logic + dedup record in ONE transaction
        val intent = transactionalOperator.executeAndAwait {
            val result = paymentService.authorizePayment(
                orderId = event.orderId,
                amount = event.amount
            )
            processedEventRepository.save(ProcessedEvent(eventId, Instant.now()))
            result
        }

        // 3. Publish downstream event (outside TX — at-least-once safe)
        paymentKafkaProducer.sendPaymentAuthorized(
            PaymentAuthorizedEvent(
                orderId = event.orderId,
                paymentId = intent.id,
                amount = intent.amount
            )
        )

        ack.acknowledge()
        logger.info("Payment authorized for orderId={}, paymentId={}", event.orderId, intent.id)
    }
}
