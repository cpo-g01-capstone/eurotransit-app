package com.eurotransit.payments.web

import com.eurotransit.payments.event.PaymentAuthorizedEvent
import com.eurotransit.payments.kafka.PaymentKafkaProducer
import com.eurotransit.payments.service.PaymentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

data class AuthorizeRequest(val orderId: UUID, val amount: BigDecimal)
data class AuthorizeResponse(
    val paymentId: UUID,
    val orderId: UUID,
    val amount: BigDecimal,
    val status: String,
)

/**
 * Synchronous payment authorization — the ADR 0018 decision point.
 *
 * Called by Orders where a decision is needed NOW ("can this customer's money be
 * authorized?"). Replaces the previous Kafka hop (`inventory-reserved` consumer,
 * removed in the same change) so the caller can wrap this call in a circuit
 * breaker + timeout + bounded retry.
 *
 * Idempotency (money-path requirement): [PaymentService.authorizePayment] dedups on
 * orderId (UNIQUE payment_intents.order_id) and returns the previously recorded
 * intent on replay — a retried call NEVER double-charges. The `Idempotency-Key`
 * header (= orderId, Stripe-style) documents caller intent and is validated to
 * match the body.
 *
 * The `payment-authorized` event is still published after authorization, so the
 * downstream confirmation flow (Orders consumer) is unchanged. Re-publishing on an
 * idempotent replay is at-least-once-safe: the consumer dedups on
 * `{orderId}:payment-authorized`.
 */
@RestController
@RequestMapping("/payments")
class AuthorizeController(
    private val paymentService: PaymentService,
    private val producer: PaymentKafkaProducer,
) {

    @PostMapping("/authorize")
    suspend fun authorize(
        @RequestHeader("Idempotency-Key") idempotencyKey: String?,
        @RequestBody request: AuthorizeRequest,
    ): ResponseEntity<AuthorizeResponse> {
        if (idempotencyKey.isNullOrBlank() || idempotencyKey != request.orderId.toString()) {
            return ResponseEntity.badRequest().build()
        }

        val intent = paymentService.authorizePayment(request.orderId, request.amount)

        producer.sendPaymentAuthorized(
            PaymentAuthorizedEvent(
                orderId = request.orderId,
                paymentId = intent.id,
                amount = intent.amount,
            ),
        )

        return ResponseEntity.ok(
            AuthorizeResponse(
                paymentId = intent.id,
                orderId = request.orderId,
                amount = intent.amount,
                status = "AUTHORIZED",
            ),
        )
    }
}
