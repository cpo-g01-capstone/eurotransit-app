package com.eurotransit.inventory.event

import java.time.Instant
import java.util.UUID

/** Consumed from topic `order-placed` (produced by Orders). */
data class OrderPlacedEvent(
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val timestamp: Instant = Instant.now(),
    val idempotencyKey: String = ""
)

/** Published to topic `inventory-reserved` after seats are reserved. */
data class InventoryReservedEvent(
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val reservationId: UUID,
    val amount: java.math.BigDecimal,
    val timestamp: Instant = Instant.now()
)
