package com.eurotransit.notifications.service

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.email.EmailSender
import com.eurotransit.notifications.persistence.SentNotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NotificationServiceTest {

    private val repository = mockk<SentNotificationRepository>()
    private val emailSender = mockk<EmailSender>(relaxed = true)
    private val service = NotificationService(repository, emailSender)
    private val event = OrderConfirmedEvent("order-1", "alice@example.com", null)

    @Test
    fun `fresh order is sent then marked SENT`() = runTest {
        coEvery { repository.claim("order-1") } returns 1L
        coEvery { repository.updateStatus("order-1", "SENT") } returns 1L

        service.handle(event)

        coVerify(exactly = 1) { emailSender.send(event) }
        coVerify(exactly = 1) { repository.updateStatus("order-1", "SENT") }
    }

    @Test
    fun `already SENT order is skipped`() = runTest {
        coEvery { repository.claim("order-1") } returns 0L
        coEvery { repository.findStatus("order-1") } returns "SENT"

        service.handle(event)

        coVerify(exactly = 0) { emailSender.send(any()) }
    }

    @Test
    fun `PENDING order from a prior crash is retried`() = runTest {
        coEvery { repository.claim("order-1") } returns 0L
        coEvery { repository.findStatus("order-1") } returns "PENDING"
        coEvery { repository.updateStatus("order-1", "SENT") } returns 1L

        service.handle(event)

        coVerify(exactly = 1) { emailSender.send(event) }
    }

    @Test
    fun `send failure propagates and does not mark SENT`() = runTest {
        coEvery { repository.claim("order-1") } returns 1L
        coEvery { emailSender.send(event) } throws RuntimeException("smtp down")

        assertThrows(RuntimeException::class.java) {
            runBlocking { service.handle(event) }
        }
        coVerify(exactly = 0) { repository.updateStatus("order-1", "SENT") }
    }
}
