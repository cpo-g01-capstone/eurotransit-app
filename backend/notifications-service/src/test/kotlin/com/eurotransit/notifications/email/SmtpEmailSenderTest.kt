package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

class SmtpEmailSenderTest {

    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val sender = SmtpEmailSender(
        mailSender,
        SimpleMeterRegistry(),
        fromAddress = "noreply@eurotransit.test",
        defaultToAddress = "customer@demo.eurotransit.test",
    )

    @Test
    fun `sends a confirmation addressed to the event contact`() = runTest {
        val captured = slot<SimpleMailMessage>()
        every { mailSender.send(capture(captured)) } returns Unit

        sender.send(OrderConfirmedEvent(orderId = "order-1", customerContact = "rider@example.com"))

        assertEquals("rider@example.com", captured.captured.to?.single())
        assertEquals("noreply@eurotransit.test", captured.captured.from)
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `falls back to the default inbox when the order has no contact`() = runTest {
        val captured = slot<SimpleMailMessage>()
        every { mailSender.send(capture(captured)) } returns Unit

        sender.send(OrderConfirmedEvent(orderId = "order-3", customerContact = null))

        // setTo(null) used to throw at send time, retrying every contact-less
        // order into the DLT even though the email is optional by design.
        assertEquals("customer@demo.eurotransit.test", captured.captured.to?.single())
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `treats a blank contact like a missing one`() = runTest {
        val captured = slot<SimpleMailMessage>()
        every { mailSender.send(capture(captured)) } returns Unit

        sender.send(OrderConfirmedEvent(orderId = "order-4", customerContact = "   "))

        assertEquals("customer@demo.eurotransit.test", captured.captured.to?.single())
    }

    @Test
    fun `a send failure propagates so the caller can retry toward the DLT`() {
        every { mailSender.send(any<SimpleMailMessage>()) } throws RuntimeException("smtp down")

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                sender.send(OrderConfirmedEvent(orderId = "order-2", customerContact = "x@example.com"))
            }
        }
    }
}
