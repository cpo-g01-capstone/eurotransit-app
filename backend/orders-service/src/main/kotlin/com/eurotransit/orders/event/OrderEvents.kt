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

/**
 * Consumed from topic `payment-authorized` (produced by Payments).
 * `amount` mirrors the producer's field (final-audit #5 — Jackson silently
 * dropped it before); nullable so events published before the field existed
 * still deserialize.
 */
data class PaymentAuthorizedEvent(
    val orderId: UUID,
    val paymentId: UUID,
    val amount: java.math.BigDecimal? = null,
    val timestamp: Instant = Instant.now()
)

/** Published to topic `order-confirmed` after payment is authorized. */
data class OrderConfirmedEvent(
    val orderId: UUID,
    val timestamp: Instant = Instant.now()
)

/**
 * Published to topic `order-failed` when an order is terminally failed
 * (payment redeliveries exhausted — see KafkaErrorHandlingConfig). The
 * seat-release compensation (D4): Inventory consumes this and releases any
 * RESERVED reservation for the order. Carries only the orderId — Inventory
 * owns the reservation lookup, so the event cannot go stale.
 */
data class OrderFailedEvent(
    val orderId: UUID,
    val reason: String,
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
