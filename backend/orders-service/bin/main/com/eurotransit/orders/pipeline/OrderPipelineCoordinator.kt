package com.eurotransit.orders.pipeline

import com.eurotransit.orders.domain.OrderRepository
import com.eurotransit.orders.domain.OrderStatus
import com.eurotransit.orders.events.KafkaEventPublisher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class OrderPipelineCoordinator(
    private val orderRepository: OrderRepository,
    private val kafkaEventPublisher: KafkaEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["order-placed"], groupId = "orders-pipeline-group")
    suspend fun onOrderPlaced(event: com.eurotransit.orders.events.OrderPlacedEvent) = coroutineScope {
        val orderId = event.orderId
        log.info("Pipeline Stage 1: Processing order-placed for {}", orderId)
        
        val order = orderRepository.findById(orderId).awaitSingleOrNull() ?: return@coroutineScope
        if (order.status != OrderStatus.DRAFT) return@coroutineScope
        
        try {
            // TODO: Synchronous call to Inventory Service via WebClient
            log.info("Mock: Reserved inventory for order {}", orderId)
            
            withContext(NonCancellable) {
                val updatedOrder = order.copy(status = OrderStatus.RESERVED)
                orderRepository.save(updatedOrder).awaitSingleOrNull()
                kafkaEventPublisher.publishInventoryReserved(orderId)
            }
        } catch (e: Exception) {
            log.error("Failed to reserve inventory for order {}", orderId, e)
            withContext(NonCancellable) {
                val failedOrder = order.copy(status = OrderStatus.FAILED, failureReason = "Inventory reservation failed: ${e.message}")
                orderRepository.save(failedOrder).awaitSingleOrNull()
            }
        }
    }

    @KafkaListener(topics = ["inventory-reserved"], groupId = "orders-pipeline-group")
    suspend fun onInventoryReserved(event: com.eurotransit.orders.events.InventoryReservedEvent) = coroutineScope {
        val orderId = event.orderId
        log.info("Pipeline Stage 2: Processing inventory-reserved for {}", orderId)
        
        val order = orderRepository.findById(orderId).awaitSingleOrNull() ?: return@coroutineScope
        if (order.status != OrderStatus.RESERVED) return@coroutineScope
        
        try {
            // TODO: Synchronous call to Payments Service via WebClient
            log.info("Mock: Authorized payment for order {}", orderId)
            
            withContext(NonCancellable) {
                val updatedOrder = order.copy(status = OrderStatus.PAID)
                orderRepository.save(updatedOrder).awaitSingleOrNull()
                kafkaEventPublisher.publishPaymentAuthorized(orderId)
            }
        } catch (e: Exception) {
            log.error("Failed to authorize payment for order {}", orderId, e)
            withContext(NonCancellable) {
                val failedOrder = order.copy(status = OrderStatus.FAILED, failureReason = "Payment authorization failed: ${e.message}")
                orderRepository.save(failedOrder).awaitSingleOrNull()
                // Saga compensation: Release inventory since payment failed
                log.info("Saga Compensation: Emitting inventory-release for order {}", orderId)
                kafkaEventPublisher.publishInventoryRelease(orderId)
            }
        }
    }

    @KafkaListener(topics = ["payment-authorized"], groupId = "orders-pipeline-group")
    suspend fun onPaymentAuthorized(event: com.eurotransit.orders.events.PaymentAuthorizedEvent) = coroutineScope {
        val orderId = event.orderId
        log.info("Pipeline Stage 3: Processing payment-authorized for {}", orderId)
        
        val order = orderRepository.findById(orderId).awaitSingleOrNull() ?: return@coroutineScope
        if (order.status != OrderStatus.PAID) return@coroutineScope
        
        withContext(NonCancellable) {
            val updatedOrder = order.copy(status = OrderStatus.CONFIRMED)
            orderRepository.save(updatedOrder).awaitSingleOrNull()
            kafkaEventPublisher.publishOrderConfirmed(orderId)
            log.info("Order {} confirmed successfully!", orderId)
        }
    }
}
