package com.eurotransit.catalog.event

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Consumed from topic `inventory-reserved` (produced by Inventory).
 * Field shape mirrors the producer's event exactly. Catalog uses it to keep its
 * best-effort availability cache warm — it never acts on it transactionally.
 */
data class InventoryReservedEvent(
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val reservationId: UUID,
    val amount: BigDecimal,
    val timestamp: Instant = Instant.now()
)
