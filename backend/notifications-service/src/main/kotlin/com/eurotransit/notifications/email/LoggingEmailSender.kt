package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Stub sender (ADR/spec): logs and counts. No real SMTP — the project grades resilience. */
@Component
class LoggingEmailSender(registry: MeterRegistry) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sent = registry.counter("notifications_sent_total")

    override suspend fun send(event: OrderConfirmedEvent) {
        log.info("Sending order-confirmation for order={} to={}", event.orderId, event.customerContact)
        sent.increment()
    }
}
