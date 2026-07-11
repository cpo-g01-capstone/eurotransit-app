package com.eurotransit.orders.service

import com.eurotransit.orders.event.CreateOrderRequest
import com.eurotransit.orders.event.OrderPlacedEvent
import com.eurotransit.orders.event.OrderResponse
import com.eurotransit.orders.kafka.OrderKafkaProducer
import com.eurotransit.orders.model.IdempotencyRecord
import com.eurotransit.orders.model.Order
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.repository.IdempotencyRecordRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

@Service
class OrderService(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val orderKafkaProducer: OrderKafkaProducer,
    private val transactionalOperator: TransactionalOperator,
    // Explicit INSERTs: our entities carry app-assigned @Id values, and
    // repository.save() maps "id present" to UPDATE (0 rows -> error).
    // See app ADR 0007 / agent-log case 17.
    private val entityTemplate: R2dbcEntityTemplate,
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
            // insert(), NOT repository.save(): both rows carry app-assigned ids,
            // and save() would issue an UPDATE against a row that doesn't exist
            // (TransientDataAccessResourceException). This 500 was the FIRST
            // real POST through the gateway — agent-log case 17.
            entityTemplate.insert(
                Order(id = orderId, status = OrderStatus.DRAFT)
            ).awaitSingle()
            entityTemplate.insert(
                IdempotencyRecord(
                    idempotencyKey = idempotencyKey,
                    responsePayload = responseJson,
                    createdAt = Instant.now()
                )
            ).awaitSingle()
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
