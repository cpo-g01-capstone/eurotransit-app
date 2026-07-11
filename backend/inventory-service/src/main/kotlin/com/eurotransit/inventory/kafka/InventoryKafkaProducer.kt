package com.eurotransit.inventory.kafka

import com.eurotransit.inventory.event.InventoryReservedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class InventoryKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun sendInventoryReserved(event: InventoryReservedEvent) {
        logger.info("Publishing inventory-reserved for orderId={}", event.orderId)
        kafkaTemplate.send(TOPIC_INVENTORY_RESERVED, event.orderId.toString(), event)
    }

    companion object {
        const val TOPIC_INVENTORY_RESERVED = "inventory-reserved"
    }
}
