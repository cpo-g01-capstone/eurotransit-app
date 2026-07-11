package com.eurotransit.payments.persistence

import com.eurotransit.payments.AbstractIntegrationTest
import com.eurotransit.payments.repository.PaymentIntentRepository
import com.eurotransit.payments.service.PaymentService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

/**
 * Persistence round-trip for payment authorization against a real PostgreSQL.
 *
 * Regression guard: `save()` on the pre-assigned PaymentIntent id issued an
 * UPDATE for a row that does not exist yet (insert-first bug, see
 * OrderPersistenceIT in orders-service for the full story).
 */
class PaymentPersistenceIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var paymentService: PaymentService

    @Autowired
    lateinit var paymentIntentRepository: PaymentIntentRepository

    @Test
    fun `authorizePayment persists the payment intent`() = runBlocking {
        val orderId = UUID.randomUUID()

        val intent = paymentService.authorizePayment(orderId, BigDecimal("19.90"))

        val saved = paymentIntentRepository.findByOrderId(orderId)
        assertNotNull(saved, "the payment intent row must exist after authorization")
        assertEquals(intent.id, saved!!.id)
        assertEquals(0, BigDecimal("19.90").compareTo(saved.amount))
    }

    @Test
    fun `authorizePayment is idempotent per order — no double charge`() = runBlocking {
        val orderId = UUID.randomUUID()

        val first = paymentService.authorizePayment(orderId, BigDecimal("10.00"))
        val second = paymentService.authorizePayment(orderId, BigDecimal("10.00"))

        assertEquals(first.id, second.id, "replay must return the existing intent, not a new charge")
    }
}
