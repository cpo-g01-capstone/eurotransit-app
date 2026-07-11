package com.eurotransit.orders.kafka

import com.eurotransit.orders.client.PaymentsClient
import com.eurotransit.orders.event.InventoryReservedEvent
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
 * Consumes `inventory-reserved` and drives the ADR 0018 synchronous decision point:
 * transition the order to RESERVED, then authorize the payment via the
 * breaker-wrapped [PaymentsClient] call.
 *
 * This consumer MOVED here from Payments (whose `inventory-reserved` Kafka consumer
 * is removed in the same change): the spec requires the authorization to be a
 * synchronous Orders → Payments call so that a circuit breaker can protect it.
 * It also closes a latent gap: nothing transitioned DRAFT → RESERVED before, so
 * the payment-authorized handler (RESERVED → CONFIRMED) could never succeed.
 *
 * Failure semantics (the breaker's "safe fallback", ADR 0018):
 * - the dedup record is saved ONLY AFTER a successful authorization — on any
 *   payment failure the event stays unprocessed, the exception propagates, and
 *   the container's DefaultErrorHandler redelivers with exponential backoff
 *   (the order stays RESERVED with the payment effectively queued for retry);
 * - every redelivery is safe: the RESERVED transition is a conditional no-op on
 *   replay and authorize is idempotent (Idempotency-Key = orderId);
 * - when redeliveries are exhausted, the error handler's recoverer marks the
 *   order FAILED (see KafkaErrorHandlingConfig).
 *
 * NOTE — deliberately NOT a `suspend` @KafkaListener: with this Spring Kafka
 * version a suspend listener swallows handler exceptions, so the error handler
 * (and therefore the whole fallback above) would never fire. Non-suspend +
 * runBlocking bridge, same as Notifications (agent-log case 12, app ADR-004;
 * the runBlocking exception to the repo rule is decision D5).
 */
@Component
class InventoryReservedConsumer(
    private val orderRepository: OrderRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val paymentsClient: PaymentsClient,
    private val transactionalOperator: TransactionalOperator,
    private val shutdownManager: GracefulShutdownManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["inventory-reserved"],
        groupId = "orders-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.orders.event.InventoryReservedEvent"
        ],
    )
    fun onInventoryReserved(record: ConsumerRecord<String, InventoryReservedEvent?>, ack: Acknowledgment) {
        val event = record.value() ?: run {
            // Poison/undeserializable payload: ack and skip (ErrorHandlingDeserializer yields null).
            ack.acknowledge()
            return
        }

        // Cooperative shutdown: skip without ack → redelivered elsewhere after rebalance.
        if (!shutdownManager.isAcceptingTraffic()) {
            logger.info("Shutting down — not processing inventory-reserved for order {}", event.orderId)
            return
        }

        runBlocking { handle(event) } // bridge: exceptions must reach the error handler (see class doc)
        ack.acknowledge()
    }

    private suspend fun handle(event: InventoryReservedEvent) {
        val eventId = "${event.orderId}:inventory-reserved"

        // 1. Dedup (read-before-write; the record is written only on success, step 4)
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            return
        }

        shutdownManager.trackInflight {
            // 2. Seats are held → order becomes RESERVED. Conditional transition:
            //    a replay (already RESERVED or beyond) is a no-op, not an error.
            transactionalOperator.executeAndAwait {
                val updated = orderRepository.updateStatus(
                    id = event.orderId,
                    newStatus = OrderStatus.RESERVED,
                    expectedStatus = OrderStatus.DRAFT,
                )
                if (updated == 0) {
                    logger.info("Order {} not in DRAFT (replay or out-of-band change) — continuing", event.orderId)
                }
            }

            // Cooperative cancellation checkpoint before the remote call.
            coroutineContext.ensureActive()

            // 3. The synchronous decision (ADR 0018): breaker + timeout + bounded retry.
            //    Throws on failure/open-breaker → error-handler redelivery (class doc).
            val auth = paymentsClient.authorize(event.orderId, event.amount)
            logger.info(
                "Payment authorized synchronously for order {}: paymentId={}, amount={}",
                event.orderId, auth.paymentId, auth.amount,
            )

            // 4. Mark the event processed ONLY after success, so failed attempts
            //    are retried by redelivery instead of being lost.
            transactionalOperator.executeAndAwait {
                // insert(), not save(): app-assigned @Id would map to UPDATE (agent-log case 17)
                entityTemplate.insert(ProcessedEvent(eventId, Instant.now())).awaitSingle()
            }
        }
    }
}
