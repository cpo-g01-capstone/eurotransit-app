package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LoggingEmailSenderTest {

    @Test
    fun `send increments the sent counter`() = runTest {
        val registry = SimpleMeterRegistry()
        val sender = LoggingEmailSender(registry)

        sender.send(OrderConfirmedEvent("order-1", "alice@example.com", null))

        assertEquals(1.0, registry.counter("notifications_sent_total").count())
    }
}
