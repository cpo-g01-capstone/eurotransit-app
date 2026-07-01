package com.eurotransit.payments.service

import com.eurotransit.payments.model.PaymentIntent
import com.eurotransit.payments.repository.PaymentIntentRepository
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/**
 * Payment authorization logic with idempotency.
 *
 * Idempotency is guaranteed at two levels:
 * 1. Application level: check for existing payment intent by orderId
 * 2. Database level: unique index on payment_intents(order_id)
 *
 * In production, this would call an external PSP (Stripe, PayPal, etc.).
 * For the capstone, we simulate the authorization with a small delay.
 */
@Service
class PaymentService(
    private val paymentIntentRepository: PaymentIntentRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Authorizes a payment for the given order.
     * Returns the existing PaymentIntent if already authorized (idempotent).
     */
    suspend fun authorizePayment(
        orderId: UUID,
        amount: BigDecimal
    ): PaymentIntent {
        // 1. Idempotency: check if already authorized for this order
        val existing = paymentIntentRepository.findByOrderId(orderId)
        if (existing != null) {
            logger.info(
                "Payment already authorized for orderId={} — returning existing paymentId={}",
                orderId, existing.id
            )
            return existing
        }

        // 2. Simulate PSP authorization (coroutine-friendly delay, not Thread.sleep)
        logger.info("Authorizing payment of {} for orderId={}", amount, orderId)
        delay(100) // Simulates external PSP call latency

        // 3. Persist payment intent
        val intent = paymentIntentRepository.save(
            PaymentIntent(
                orderId = orderId,
                amount = amount,
                idempotencyKey = "${orderId}:payment"
            )
        )

        logger.info("Payment authorized: paymentId={}, orderId={}, amount={}", intent.id, orderId, amount)
        return intent
    }
}
