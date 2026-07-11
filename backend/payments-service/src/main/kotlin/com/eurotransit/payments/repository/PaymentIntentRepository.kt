package com.eurotransit.payments.repository

import com.eurotransit.payments.model.PaymentIntent
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface PaymentIntentRepository : CoroutineCrudRepository<PaymentIntent, UUID> {

    /** Check for existing payment intent (idempotency — prevents double-charge). */
    suspend fun findByOrderId(orderId: UUID): PaymentIntent?
}
