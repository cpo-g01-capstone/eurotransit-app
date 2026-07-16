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
    private val sender = SmtpEmailSender(mailSender, SimpleMeterRegistry(), fromAddress = "noreply@eurotransit.test")

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
    fun `a send failure propagates so the caller can retry toward the DLT`() {
        every { mailSender.send(any<SimpleMailMessage>()) } throws RuntimeException("smtp down")

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                sender.send(OrderConfirmedEvent(orderId = "order-2", customerContact = "x@example.com"))
            }
        }
    }
}
