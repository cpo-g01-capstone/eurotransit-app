package com.eurotransit.orders.events

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {

    fun publishOrderPlaced(orderId: UUID) {
        kafkaTemplate.send("order-placed", orderId.toString(), OrderPlacedEvent(orderId))
    }
    
    fun publishInventoryReserved(orderId: UUID) {
        kafkaTemplate.send("inventory-reserved", orderId.toString(), InventoryReservedEvent(orderId))
    }
    
    fun publishPaymentAuthorized(orderId: UUID) {
        kafkaTemplate.send("payment-authorized", orderId.toString(), PaymentAuthorizedEvent(orderId))
    }
    
    fun publishOrderConfirmed(orderId: UUID) {
        kafkaTemplate.send("order-confirmed", orderId.toString(), OrderConfirmedEvent(orderId))
    }

    fun publishInventoryRelease(orderId: UUID) {
        kafkaTemplate.send("inventory-release", orderId.toString(), InventoryReleaseEvent(orderId))
    }

    fun publishOrderCancelled(orderId: UUID) {
        kafkaTemplate.send("order-cancelled", orderId.toString(), OrderCancelledEvent(orderId))
    }
}

data class OrderPlacedEvent(val orderId: UUID)
data class InventoryReservedEvent(val orderId: UUID)
data class PaymentAuthorizedEvent(val orderId: UUID)
data class OrderConfirmedEvent(val orderId: UUID)
data class InventoryReleaseEvent(val orderId: UUID)
data class OrderCancelledEvent(val orderId: UUID)
