package com.eurotransit.catalog.kafka

import com.eurotransit.catalog.cache.RouteCache
import com.eurotransit.catalog.event.InventoryReservedEvent
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
        properties = [
            "spring.json.value.default.type=com.eurotransit.catalog.event.InventoryReservedEvent",
            "auto.offset.reset=earliest",
        ],
    )
    fun onInventoryReserved(event: InventoryReservedEvent?) {
        if (event == null) return // poison message → advisory cache just skips it
        cache.applyReservation(event.reservationId, event.routeId, event.seats)
        logger.debug("Cache updated: route {} -{} seats", event.routeId, event.seats)
    }
}
