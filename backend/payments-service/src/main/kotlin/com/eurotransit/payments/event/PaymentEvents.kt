package com.eurotransit.payments.event

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Consumed from topic `inventory-reserved` (produced by Inventory). */
data class InventoryReservedEvent(
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val reservationId: UUID,
    val amount: BigDecimal,
    val timestamp: Instant = Instant.now()
)

/** Published to topic `payment-authorized` after payment authorization. */
data class PaymentAuthorizedEvent(
    val orderId: UUID,
    val paymentId: UUID,
    val amount: BigDecimal,
    val timestamp: Instant = Instant.now()
)
