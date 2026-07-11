package com.eurotransit.orders.client

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.math.BigDecimal
import java.util.UUID

data class AuthorizePaymentRequest(val orderId: UUID, val amount: BigDecimal)
data class AuthorizePaymentResponse(
    val paymentId: UUID,
    val orderId: UUID,
    val amount: BigDecimal,
    val status: String,
)

/**
 * Synchronous Orders → Payments call, resilience-wrapped per ADR 0018.
 *
 * Decorator order: Retry ( CircuitBreaker ( 2s-timeout WebClient call ) ):
 * - every attempt (initial + retries) is recorded by the breaker's sliding window,
 *   so a struggling Payments trips it quickly;
 * - when the breaker is OPEN, CallNotPermittedException fails FAST (no network
 *   attempt, no thread held) and is configured as non-retryable — it propagates
 *   to the Kafka error handler, whose exponential redelivery is the "queued
 *   retry" fallback (the order stays RESERVED, never an unbounded hang);
 * - the retry itself is bounded (3 attempts) with exponential backoff + jitter,
 *   safe ONLY because authorize is idempotent (Idempotency-Key = orderId).
 *
 * Policy values (window 20/min 10, 50% failure or slow-call rate, 2s slow-call
 * threshold, 30s open → half-open with 5 probes) live in application.yml and are
 * the ADR 0018 team-proposed defaults, to tune after the k6 baseline.
 */
@Component
class PaymentsClient(
    @Qualifier("paymentsWebClient") private val webClient: WebClient,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry,
) {
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("payments")
    private val retry = retryRegistry.retry("payments")

    suspend fun authorize(orderId: UUID, amount: BigDecimal): AuthorizePaymentResponse =
        retry.executeSuspendFunction {
            circuitBreaker.executeSuspendFunction {
                webClient.post()
                    .uri("/payments/authorize")
                    .header("Idempotency-Key", orderId.toString())
                    .bodyValue(AuthorizePaymentRequest(orderId, amount))
                    .retrieve()
                    .awaitBody()
            }
        }
}
