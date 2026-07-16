package com.eurotransit.orders.controller

import com.eurotransit.orders.event.CreateOrderRequest
import com.eurotransit.orders.event.OrderResponse
import com.eurotransit.orders.repository.OrderRepository
import com.eurotransit.orders.service.OrderService
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService,
    private val orderRepository: OrderRepository,
    rateLimiterRegistry: RateLimiterRegistry
) {

    /**
     * Reads the current state of an order. The POST returns 202 while the
     * pipeline runs asynchronously — this is how a client (and the k6 E2E
     * suite, tests/k6/checkout-e2e.js) observes the order reaching a terminal
     * state (CONFIRMED / FAILED). Read-only, no rate limiting.
     */
    @GetMapping("/{id}")
    suspend fun getOrder(@PathVariable id: UUID): ResponseEntity<OrderResponse> {
        val order = orderRepository.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            OrderResponse(
                orderId = order.id,
                status = order.status.name,
                message = "",
                customerContact = order.customerContact
            )
        )
    }

    // Backpressure / load shedding (Pillar C, ADR 0018): beyond the configured
    // rate the API refuses work with an immediate 429 instead of queuing toward
    // collapse. "Backpressure is not failure; it is controlled refusal" — 429s
    // are excluded from the SLO error budget (docs/design/slo-definitions.md).
    private val checkoutLimiter = rateLimiterRegistry.rateLimiter("checkout")

    /**
     * Creates a new order. Requires an `Idempotency-Key` header to prevent
     * duplicate order creation on retries.
     *
     * - First call: returns 201 Created with the new order
     * - Duplicate call (same key): returns 200 OK with the cached response
     * - Missing header: returns 400 Bad Request
     */
    @PostMapping
    suspend fun createOrder(
        @RequestHeader("Idempotency-Key") idempotencyKey: String?,
        @RequestBody request: CreateOrderRequest
    ): ResponseEntity<OrderResponse> {
        if (!checkoutLimiter.acquirePermission()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .build()
        }
        if (idempotencyKey.isNullOrBlank()) {
            return ResponseEntity.badRequest().build()
        }

        val (response, isNew) = orderService.placeOrder(idempotencyKey, request)

        return if (isNew) {
            ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }
}
