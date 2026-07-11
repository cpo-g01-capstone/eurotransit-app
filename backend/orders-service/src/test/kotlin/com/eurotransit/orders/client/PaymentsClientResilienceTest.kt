package com.eurotransit.orders.client

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.util.UUID

/**
 * The breaker's fail-fast contract (ADR 0018), without Spring context or network:
 * with the circuit OPEN, authorize() must throw CallNotPermittedException
 * IMMEDIATELY — no connection attempt (the WebClient here points at a closed
 * port: any attempt would surface as a connect error, not CallNotPermitted),
 * and the retry must NOT re-attempt it (CallNotPermittedException is configured
 * as non-retryable, mirroring application.yml).
 */
class PaymentsClientResilienceTest {

    @Test
    fun `open breaker fails fast without touching the network and without retries`() {
        val cbRegistry = CircuitBreakerRegistry.ofDefaults()
        val retryRegistry = RetryRegistry.of(
            RetryConfig.custom<Any>()
                .maxAttempts(3)
                .ignoreExceptions(CallNotPermittedException::class.java)
                .build(),
        )
        val client = PaymentsClient(
            webClient = WebClient.builder().baseUrl("http://127.0.0.1:1").build(),
            circuitBreakerRegistry = cbRegistry,
            retryRegistry = retryRegistry,
        )

        val breaker = cbRegistry.circuitBreaker("payments")
        breaker.transitionToOpenState()
        val callsBefore = breaker.metrics.numberOfNotPermittedCalls

        assertThrows<CallNotPermittedException> {
            runBlocking { client.authorize(UUID.randomUUID(), BigDecimal("19.90")) }
        }

        // exactly ONE not-permitted call: the retry did not hammer the open breaker
        assertEquals(callsBefore + 1, breaker.metrics.numberOfNotPermittedCalls)
    }
}
