package com.eurotransit.orders.controller

import com.eurotransit.orders.event.CreateOrderRequest
import com.eurotransit.orders.event.OrderResponse
import com.eurotransit.orders.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService
) {

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
