package com.eurotransit.notifications

/**
 * Payload of the `order-confirmed` topic (ADR-001). The recipient snapshot travels in the
 * event so Notifications never calls back to Orders. Align field names with the Orders producer.
 *
 * `customerContact` MUST stay NULLABLE, mirroring the Orders producer's
 * `String?` (email is optional end-to-end, design spec 2026-07-16). It was
 * previously non-null with a Kotlin default — but a Kotlin default only covers
 * an ABSENT property, and Orders serializes an explicit `"customerContact":
 * null` for contact-less orders, which a non-null type rejects: every no-email
 * confirmation was dropped/DLT'd while email orders sailed through. The
 * demo-inbox fallback now lives where it belongs, in [email.SmtpEmailSender]
 * (`notifications.email.default-to`).
 */
data class OrderConfirmedEvent(
    val orderId: String,
    val customerContact: String? = null,
    val confirmedAt: String? = null,
)
