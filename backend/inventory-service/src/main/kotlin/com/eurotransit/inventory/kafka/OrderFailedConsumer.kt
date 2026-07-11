package com.eurotransit.inventory.kafka

import com.eurotransit.inventory.event.OrderFailedEvent
import com.eurotransit.inventory.lifecycle.GracefulShutdownManager
import com.eurotransit.inventory.model.ProcessedEvent
import com.eurotransit.inventory.repository.ProcessedEventRepository
import com.eurotransit.inventory.service.InventoryService
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/**
 * Consumes `order-failed` (published by Orders when payment redeliveries are
 * exhausted) and releases the order's RESERVED seats — the seat-release
 * compensation that closes the ADR-005 known gap (decision D4).
 *
 * Idempotency, two layers:
 * 1. processed_events dedup on "orderId:order-failed" (skips clean replays);
 * 2. the RESERVED -> RELEASED conditional transition in [InventoryService.releaseSeats]
 *    (protects even a replay that races the dedup insert — seats can never be
 *    given back twice).
 *
 * NOT a `suspend` @KafkaListener: non-suspend + runBlocking bridge, the pattern
 * ratified as decision D5 (app ADR-004) — a suspend listener would swallow
 * handler exceptions and break error-handler redelivery on transient DB errors.
 */
@Component
class OrderFailedConsumer(
    private val inventoryService: InventoryService,
    private val processedEventRepository: ProcessedEventRepository,
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

        runBlocking { handle(event) } // bridge: exceptions must reach the error handler (D5)
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
                processedEventRepository.save(ProcessedEvent(eventId, Instant.now()))
            }
        }
    }
}
