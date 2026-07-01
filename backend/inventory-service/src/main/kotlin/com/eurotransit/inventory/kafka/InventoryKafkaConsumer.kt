package com.eurotransit.inventory.kafka

import com.eurotransit.inventory.event.InventoryReservedEvent
import com.eurotransit.inventory.event.OrderPlacedEvent
import com.eurotransit.inventory.model.ProcessedEvent
import com.eurotransit.inventory.repository.ProcessedEventRepository
import com.eurotransit.inventory.service.InventoryService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/**
 * Consumes `order-placed` events and reserves seats idempotently.
 *
 * Dedup pattern (from docs/design/idempotency.md):
 * 1. Read-before-write check on processed_events
 * 2. Business logic + dedup insert in one transaction
 * 3. Downstream publish outside transaction (at-least-once safe)
 */
@Component
class InventoryKafkaConsumer(
    private val inventoryService: InventoryService,
    private val processedEventRepository: ProcessedEventRepository,
    private val inventoryKafkaProducer: InventoryKafkaProducer,
    private val transactionalOperator: TransactionalOperator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["order-placed"],
        groupId = "inventory-service",
        properties = [
            "spring.json.value.default.type=com.eurotransit.inventory.event.OrderPlacedEvent"
        ]
    )
    suspend fun handleOrderPlaced(event: OrderPlacedEvent, ack: Acknowledgment) {
        val eventId = "${event.orderId}:order-placed"

        // 1. Dedup check
        if (processedEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate event {} — skipping", eventId)
            ack.acknowledge()
            return
        }

        // 2. Business logic + dedup record in ONE transaction
        var reservedEvent: InventoryReservedEvent? = null

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

            processedEventRepository.save(ProcessedEvent(eventId, Instant.now()))
        }

        // 3. Publish downstream event (outside TX — at-least-once safe)
        reservedEvent?.let { inventoryKafkaProducer.sendInventoryReserved(it) }

        ack.acknowledge()
        logger.info("Processed order-placed for orderId={}", event.orderId)
    }
}
