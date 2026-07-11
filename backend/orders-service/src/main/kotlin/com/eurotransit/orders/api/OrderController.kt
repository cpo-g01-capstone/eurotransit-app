package com.eurotransit.orders.api

import com.eurotransit.orders.api.dto.OrderRequest
import com.eurotransit.orders.api.dto.OrderResponse
import com.eurotransit.orders.domain.Order
import com.eurotransit.orders.domain.OrderRepository
import com.eurotransit.orders.events.KafkaEventPublisher
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderRepository: OrderRepository,
    private val kafkaEventPublisher: KafkaEventPublisher
) {

    @PostMapping
    suspend fun placeOrder(@RequestBody request: OrderRequest): ResponseEntity<OrderResponse> {
        val order = Order(
            customerId = request.customerId,
            routeId = request.routeId,
            seatClass = request.seatClass,
            quantity = request.quantity,
            totalAmount = request.totalAmount
        )
        
        // Save to DB
        val savedOrder = orderRepository.save(order).awaitSingle()
        
        // Publish event to Kafka
        kafkaEventPublisher.publishOrderPlaced(savedOrder.id)
        
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            OrderResponse(
                orderId = savedOrder.id,
                status = savedOrder.status.name,
                message = "Order accepted and is being processed."
            )
        )
    }
}
