package com.eurotransit.inventory.kafka

import com.eurotransit.inventory.event.OrderFailedEvent
import com.eurotransit.inventory.lifecycle.GracefulShutdownManager
import com.eurotransit.inventory.model.ProcessedEvent
import com.eurotransit.inventory.repository.ProcessedEventRepository
import com.eurotransit.inventory.service.InventoryService
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
 * Consumes `order-failed` (published by Orders when payment redeliveries are
 * exhausted) and releases the order's RESERVED seats — the seat-release
 * compensation that closes the ADR 0005 known gap.
 *
 * Idempotency, two layers:
 * 1. processed_events dedup on "orderId:order-failed" (skips clean replays);
 * 2. the RESERVED -> RELEASED conditional transition in [InventoryService.releaseSeats]
 *    (protects even a replay that races the dedup insert — seats can never be
 *    given back twice).
 *
 * NOT a `suspend` @KafkaListener: non-suspend + runBlocking bridge, the pattern
 * team-ratified (ADR 0004) — a suspend listener would swallow
 * handler exceptions and break error-handler redelivery on transient DB errors.
 */
@Component
class OrderFailedConsumer(
    private val inventoryService: InventoryService,
    private val processedEventRepository: ProcessedEventRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val transactionalOperator: TransactionalOperator,
    private val shutdownManager: GracefulShutdownManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["order-failed"],
        groupId = "inventory-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.inventory.event.OrderFailedEvent"
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
        val eventId = "${event.orderId}:order-failed"

        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            return
        }

        shutdownManager.trackInflight {
            // Release + dedup record in ONE transaction (same pattern as the
            // order-placed consumer). Throws propagate → redelivery with backoff.
            transactionalOperator.executeAndAwait {
                val released = inventoryService.releaseSeats(event.orderId)
                logger.info(
                    "order-failed for order {} ({}): released {} reservation(s)",
                    event.orderId, event.reason, released,
                )
                // insert(), not save(): app-assigned @Id would map to UPDATE (agent-log case 17)
                entityTemplate.insert(ProcessedEvent(eventId, Instant.now())).awaitSingle()
            }
        }
    }
}
