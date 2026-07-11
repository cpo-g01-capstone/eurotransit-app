package com.eurotransit.notifications

/**
 * Payload of the `order-confirmed` topic (ADR-001). The recipient snapshot travels in the
 * event so Notifications never calls back to Orders. Align field names with the Orders producer.
 *
 * `customerContact` MUST stay optional: the Orders producer sends only
 * {orderId, timestamp} — the system has no customer identity yet. With the
 * field required, Jackson rejected every REAL event (the first live checkout
 * went straight to the DLT with valueType=null) while the integration tests,
 * which build this class directly, kept passing. Defaulting keeps the contract
 * honest until a customer concept exists on the producer side.
 */
data class OrderConfirmedEvent(
    val orderId: String,
    val customerContact: String = "customer@demo.eurotransit.test",
    val confirmedAt: String? = null,
)
