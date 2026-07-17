package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * Real SMTP sender (demo: Mailtrap sandbox). Selected only when
 * `notifications.email.sender=smtp`; otherwise [LoggingEmailSender] runs.
 *
 * A send failure/timeout throws — the caller treats a throw as a failure so the
 * Kafka error handler retries and eventually routes to the DLT. SMTP is thus a
 * real degradable dependency, not a stub. The JavaMail connect/read/write
 * timeouts (application.yml) bound how long a slow SMTP can hold the thread.
 */
@Component
@ConditionalOnProperty(name = ["notifications.email.sender"], havingValue = "smtp")
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    registry: MeterRegistry,
    // Not named `from`: SimpleMailMessage exposes its own `from` bean property, which
    // would shadow this inside the apply {} block below and send with a null sender.
    @Value("\${notifications.email.from:noreply@eurotransit.test}") private val fromAddress: String,
    // The email is optional end-to-end (design spec 2026-07-16): a null/blank
    // contact falls back to this sandbox inbox instead of setTo(null), which
    // threw at send time and retried every contact-less order into the DLT —
    // the lifecycle dashboard's notification stage sat at zero for them.
    @Value("\${notifications.email.default-to:customer@demo.eurotransit.test}") private val defaultToAddress: String,
) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sent = registry.counter("notifications_sent_total")

    override suspend fun send(event: OrderConfirmedEvent) {
        val recipient = event.customerContact?.trim()?.ifBlank { null } ?: defaultToAddress
        val message = SimpleMailMessage().apply {
            setFrom(fromAddress)
            setTo(recipient)
            setSubject("Your EuroTransit booking ${event.orderId} is confirmed")
            setText("Your booking ${event.orderId} is confirmed. Thank you for riding EuroTransit.")
        }
        // JavaMailSender is blocking; keep it off the consumer/event-loop thread.
        withContext(Dispatchers.IO) { mailSender.send(message) }
        log.info("Sent order-confirmation for order={} to={}", event.orderId, recipient)
        sent.increment()
    }
}
