package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderFailedEvent
import com.eurotransit.orders.lifecycle.GracefulShutdownManager
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.model.ProcessedEvent
import com.eurotransit.orders.repository.OrderRepository
import com.eurotransit.orders.repository.ProcessedEventRepository
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

/**
 * Consumes `order-failed` and marks the order FAILED — the missing half of the
 * sold-out loop (adversarial-audit fix, #19): Inventory publishes
 * `order-failed(SOLD_OUT)` when a reservation is impossible, but until now
 * nobody in Orders listened, so the order sat in DRAFT forever.
 *
 * Sources of order-failed and why replay is safe here:
 * - Inventory's recoverer (sold-out / its redelivery exhaustion) — the DRAFT →
 *   FAILED transition below applies;
 * - Orders' own recoverer (payment exhaustion) already marked the order FAILED
 *   before publishing — both conditional transitions are then no-ops.
 * Idempotency: processed_events dedup + conditional transitions (double layer,
 * same as every money-path consumer).
 *
 * Non-suspend + runBlocking bridge (team-ratified, ADR 0004).
 */
@Component
class OrderFailedConsumer(
    private val orderRepository: OrderRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val transactionalOperator: TransactionalOperator,
    private val shutdownManager: GracefulShutdownManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["order-failed"],
        groupId = "orders-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.orders.event.OrderFailedEvent"
        ],
    )
    fun onOrderFailed(record: ConsumerRecord<String, OrderFailedEvent?>, ack: Acknowledgment) {
        val event = record.value() ?: run {
            // Poison/undeserializable payload: ack and skip (ErrorHandlingDeserializer yields null).
            ack.acknowledge()
            return
        }

        // Cooperative shutdown: skip without ack → redelivered elsewhere after rebalance.
        if (!shutdownManager.isAcceptingTraffic()) {
            logger.info("Shutting down — not processing order-failed for order {}", event.orderId)
            return
        }

        runBlocking { handle(event) } // bridge: exceptions must reach the error handler (ADR 0004)
        ack.acknowledge()
    }

    private suspend fun handle(event: OrderFailedEvent) {
        val eventId = "${event.orderId}:order-failed:orders"

        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            return
        }

        shutdownManager.trackInflight {
            transactionalOperator.executeAndAwait {
                // Terminal-failure transition: from RESERVED (payment path) or
                // DRAFT (sold-out path). Already FAILED/CONFIRMED → both no-op.
                val fromReserved = orderRepository.updateStatus(event.orderId, OrderStatus.FAILED, OrderStatus.RESERVED)
                if (fromReserved == 0) {
                    val fromDraft = orderRepository.updateStatus(event.orderId, OrderStatus.FAILED, OrderStatus.DRAFT)
                    if (fromDraft == 1) {
                        logger.info("Order {} marked FAILED from DRAFT ({})", event.orderId, event.reason)
                    }
                } else {
                    logger.info("Order {} marked FAILED from RESERVED ({})", event.orderId, event.reason)
                }
                // insert(), not save(): app-assigned @Id would map to UPDATE (agent-log case 17)
                entityTemplate.insert(ProcessedEvent(eventId, Instant.now())).awaitSingle()
            }
        }
    }
}
