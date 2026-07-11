package com.eurotransit.orders.event

import java.time.Instant
import java.util.UUID

/** Published to topic `order-placed` when a new order is created. */
data class OrderPlacedEvent(
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val timestamp: Instant = Instant.now(),
    val idempotencyKey: String
)

/**
 * Consumed from topic `inventory-reserved` (produced by Inventory).
 * Field shape mirrors the producer's InventoryReservedEvent exactly.
 * Triggers the synchronous Orders → Payments authorization (ADR 0018).
 */
data class InventoryReservedEvent(
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val reservationId: UUID,
    val amount: java.math.BigDecimal,
    val timestamp: Instant = Instant.now()
)

/** Consumed from topic `payment-authorized` (produced by Payments). */
data class PaymentAuthorizedEvent(
    val orderId: UUID,
    val paymentId: UUID,
    val timestamp: Instant = Instant.now()
)

/** Published to topic `order-confirmed` after payment is authorized. */
data class OrderConfirmedEvent(
    val orderId: UUID,
    val timestamp: Instant = Instant.now()
)

/** Request DTO for POST /orders. */
data class CreateOrderRequest(
    val routeId: UUID,
    val seats: Int
)

/** Response DTO returned from POST /orders. */
data class OrderResponse(
    val orderId: UUID,
    val status: String,
    val message: String
)
