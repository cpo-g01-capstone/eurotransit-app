package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Stub sender: logs and counts, no real SMTP. Default sender (used by tests and
 * whenever `notifications.email.sender` is unset or `logging`); [SmtpEmailSender]
 * takes over when that property is `smtp`.
 */
@Component
@ConditionalOnProperty(name = ["notifications.email.sender"], havingValue = "logging", matchIfMissing = true)
class LoggingEmailSender(registry: MeterRegistry) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sent = registry.counter("notifications_sent_total")

    override suspend fun send(event: OrderConfirmedEvent) {
        log.info("Sending order-confirmation for order={} to={}", event.orderId, event.customerContact)
        sent.increment()
    }
}
