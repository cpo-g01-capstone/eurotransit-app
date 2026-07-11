package com.eurotransit.notifications

/**
 * Payload of the `order-confirmed` topic (ADR-001). The recipient snapshot travels in the
 * event so Notifications never calls back to Orders. Align field names with the Orders producer.
 */
data class OrderConfirmedEvent(
    val orderId: String,
    val customerContact: String,
    val confirmedAt: String? = null,
)
