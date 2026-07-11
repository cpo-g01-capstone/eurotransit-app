package com.eurotransit.catalog.kafka

import com.eurotransit.catalog.cache.RouteCache
import com.eurotransit.catalog.event.InventoryReservedEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Keeps the browsing cache warm from `inventory-reserved` events. Deliberately
 * different from the money-path consumers:
 *
 *  - PER-INSTANCE consumer group (random suffix): every Catalog replica must see
 *    EVERY event — a shared group would split the stream and the replicas'
 *    caches would diverge. Cache-building consumers are broadcast consumers.
 *  - `auto.offset.reset=earliest`: a fresh pod replays the topic and rebuilds
 *    the cache from history — this is what makes the in-memory choice safe.
 *  - no manual ack, no dedup table, no error handler chain: losing or skipping
 *    an event only makes the ADVISORY availability slightly stale, which is
 *    Catalog's contract (AP/EL). The CP reservation path owns correctness.
 */
@Component
class InventoryReservedListener(private val cache: RouteCache) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["inventory-reserved"],
        groupId = "#{'catalog-cache-' + T(java.util.UUID).randomUUID().toString()}",
        // The target type lives in application.yml (spring.json.value.default.type):
        // the listener-level property never reached the delegate deserializer in
        // production — values came out null, the direct-payload parameter was then
        // rejected (MethodArgumentNotValidException) and the cache froze at the
        // seed values while offsets kept committing. ConsumerRecord signature =
        // the same null-safe pattern every other consumer in the system uses.
        properties = ["auto.offset.reset=earliest"],
    )
    fun onInventoryReserved(record: ConsumerRecord<String, InventoryReservedEvent?>) {
        val event = record.value() ?: return // poison message → advisory cache just skips it
        cache.applyReservation(event.reservationId, event.routeId, event.seats)
        logger.info("Cache updated: route {} -{} seats (order {})", event.routeId, event.seats, event.orderId)
    }
}
