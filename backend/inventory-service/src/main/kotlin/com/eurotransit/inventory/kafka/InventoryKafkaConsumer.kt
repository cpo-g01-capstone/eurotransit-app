package com.eurotransit.inventory.kafka

import com.eurotransit.inventory.event.InventoryReservedEvent
import com.eurotransit.inventory.event.OrderPlacedEvent
import com.eurotransit.inventory.lifecycle.GracefulShutdownManager
import com.eurotransit.inventory.model.ProcessedEvent
import com.eurotransit.inventory.repository.ProcessedEventRepository
import com.eurotransit.inventory.service.InventoryService
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
 * Consumes `order-placed` events and reserves seats idempotently.
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
 * Spring Kafka version swallows handler exceptions, so InsufficientSeats or a DB
 * failure here would never reach the DefaultErrorHandler — no redelivery, no
 * sold-out recoverer, order stuck in DRAFT forever. Non-suspend + runBlocking
 * bridge, the team-ratified bridge pattern (ADR 0004, agent-log case 12). The
 * sold-out path is completed by KafkaErrorHandlingConfig: InsufficientSeats is
 * non-retryable → the recoverer publishes order-failed(SOLD_OUT) → Orders marks
 * the order FAILED.
 */
@Component
class InventoryKafkaConsumer(
    private val inventoryService: InventoryService,
    private val processedEventRepository: ProcessedEventRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val inventoryKafkaProducer: InventoryKafkaProducer,
    private val transactionalOperator: TransactionalOperator,
    private val shutdownManager: GracefulShutdownManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["order-placed"],
        groupId = "inventory-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.inventory.event.OrderPlacedEvent"
        ]
    )
    fun handleOrderPlaced(record: ConsumerRecord<String, OrderPlacedEvent?>, ack: Acknowledgment) {
        val event = record.value() ?: run {
            // Poison/undeserializable payload: ack and skip.
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
        logger.info("Processed order-placed for orderId={}", event.orderId)
    }

    private suspend fun handle(event: OrderPlacedEvent) {
        val eventId = "${event.orderId}:order-placed"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            return
        }

        // 2-3. Track as in-flight so shutdown waits for completion
        var reservedEvent: InventoryReservedEvent? = null

        shutdownManager.trackInflight {
            // 2. Business logic + dedup record in ONE transaction
            transactionalOperator.executeAndAwait {
                val result = inventoryService.reserveSeats(
                    orderId = event.orderId,
                    routeId = event.routeId,
                    seats = event.seats
                )

                if (result != null) {
                    val (reservation, totalAmount) = result
                    reservedEvent = InventoryReservedEvent(
                        orderId = event.orderId,
                        routeId = event.routeId,
                        seats = event.seats,
                        reservationId = reservation.id,
                        amount = totalAmount
                    )
                }

                // insert(), not save(): app-assigned @Id would map to UPDATE (agent-log case 17)
                entityTemplate.insert(ProcessedEvent(eventId, Instant.now())).awaitSingle()
            }

            // Cooperative cancellation checkpoint before downstream publish
            coroutineContext.ensureActive()

            // 3. Publish downstream event (outside TX — at-least-once safe)
            reservedEvent?.let { inventoryKafkaProducer.sendInventoryReserved(it) }
        }
    }
}
