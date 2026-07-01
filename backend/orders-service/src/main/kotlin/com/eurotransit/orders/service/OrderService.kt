package com.eurotransit.orders.service

import com.eurotransit.orders.event.CreateOrderRequest
import com.eurotransit.orders.event.OrderPlacedEvent
import com.eurotransit.orders.event.OrderResponse
import com.eurotransit.orders.kafka.OrderKafkaProducer
import com.eurotransit.orders.model.IdempotencyRecord
import com.eurotransit.orders.model.Order
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.repository.IdempotencyRecordRepository
import com.eurotransit.orders.repository.OrderRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Places a new order with HTTP-level idempotency.
     *
     * If the [idempotencyKey] has been seen before, returns the cached response
     * without creating a new order. Otherwise, creates a DRAFT order, publishes
     * an order-placed event, and caches the response.
     *
     * @return Pair of (OrderResponse, isNew) — isNew = false for duplicates
     */
    suspend fun placeOrder(
        idempotencyKey: String,
        request: CreateOrderRequest
    ): Pair<OrderResponse, Boolean> {
        // 1. Check for existing idempotency record → return cached response
        val existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey)
        if (existing != null) {
            logger.info("Duplicate request with idempotency key {} — returning cached response", idempotencyKey)
            val cachedResponse = objectMapper.readValue(existing.responsePayload, OrderResponse::class.java)
            return Pair(cachedResponse, false)
        }

        // 2. Create order + cache response in one transaction
        val orderId = UUID.randomUUID()
        val response = OrderResponse(
            orderId = orderId,
            status = OrderStatus.DRAFT.name,
            message = "Order accepted for processing"
        )
        val responseJson = objectMapper.writeValueAsString(response)

        transactionalOperator.executeAndAwait {
            orderRepository.save(
                Order(id = orderId, status = OrderStatus.DRAFT)
            )
            idempotencyRecordRepository.save(
                IdempotencyRecord(
                    idempotencyKey = idempotencyKey,
                    responsePayload = responseJson,
                    createdAt = Instant.now()
                )
            )
        }

        // 3. Publish order-placed event (outside TX — at-least-once safe)
        orderKafkaProducer.sendOrderPlaced(
            OrderPlacedEvent(
                orderId = orderId,
                routeId = request.routeId,
                seats = request.seats,
                idempotencyKey = idempotencyKey
            )
        )

        logger.info("Order {} created as DRAFT, order-placed event published", orderId)
        return Pair(response, true)
    }
}
