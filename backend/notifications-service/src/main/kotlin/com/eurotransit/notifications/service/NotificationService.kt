package com.eurotransit.notifications.service

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.email.EmailSender
import com.eurotransit.notifications.persistence.SentNotificationRepository
import org.springframework.stereotype.Service

/**
 * Idempotent handling of `order-confirmed` (ADR-002/003):
 * claim (insert-if-absent PENDING) -> send -> mark SENT. Redelivery of a SENT/FAILED order is a
 * no-op; a PENDING order (prior incomplete attempt) is retried. A send failure is rethrown so the
 * Kafka error handler can retry and eventually route to the DLT.
 */
@Service
class NotificationService(
    private val repository: SentNotificationRepository,
    private val emailSender: EmailSender,
) {
    suspend fun handle(event: OrderConfirmedEvent) {
        val claimed = repository.claim(event.orderId)
        if (claimed == 0L) {
            when (repository.findStatus(event.orderId)) {
                "SENT", "FAILED" -> return          // dedup hit / terminal — nothing to do
                else -> Unit                        // PENDING — retry the send below
            }
        }
        emailSender.send(event)                     // may throw -> error handler retries -> DLT
        repository.updateStatus(event.orderId, "SENT")
    }
}
