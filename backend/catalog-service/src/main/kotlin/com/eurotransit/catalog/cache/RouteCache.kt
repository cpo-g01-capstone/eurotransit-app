package com.eurotransit.catalog.cache

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CatalogRoute(
    val id: UUID,
    val origin: String,
    val destination: String,
    val departureTime: Instant,
    val totalSeats: Int,
    val availableSeats: Int,
    val price: BigDecimal,
)

/**
 * Catalog's in-memory route cache — the AP/EL half of the system's consistency
 * story, IMPLEMENTED (docs/design/consistency.md, config repo):
 *
 *  - the SOURCE OF TRUTH for seats is Inventory (CP: atomic conditional UPDATE);
 *  - Catalog only serves a BEST-EFFORT view for browsing, kept warm by consuming
 *    `inventory-reserved` events. It may lag (eventual consistency) and that is
 *    the accepted trade-off: a stale listing is harmless, the CP reservation
 *    path is what prevents overselling. Availability shown here is advisory.
 *  - no database on purpose: state is disposable. On restart the listener
 *    replays the topic from the earliest offset (per-instance consumer group)
 *    and the cache converges again — stale-then-convergent, exactly AP/EL.
 *
 * Seed data mirrors inventory's V2__seed_demo_routes.sql (same deterministic
 * ids). In a fuller system Catalog would hydrate from an Inventory snapshot;
 * for the capstone the mirrored seed keeps the demo deterministic.
 */
@Component
class RouteCache {

    private val routes = ConcurrentHashMap<UUID, CatalogRoute>()

    // Best-effort dedup of event redeliveries (at-least-once): enough to avoid
    // double-decrement within a pod's lifetime; a replay-from-earliest after a
    // restart rebuilds the same state, so persistence is unnecessary.
    private val seenReservations: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    init {
        val departure = Instant.now().plus(7, ChronoUnit.DAYS)
        listOf(
            CatalogRoute(
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                origin = "Turin", destination = "Milan", departureTime = departure,
                totalSeats = 100, availableSeats = 100, price = BigDecimal("19.90"),
            ),
            CatalogRoute(
                id = UUID.fromString("00000000-0000-0000-0000-0000000000ce"),
                origin = "Rome", destination = "Naples", departureTime = departure,
                totalSeats = 2, availableSeats = 2, price = BigDecimal("24.50"),
            ),
        ).forEach { routes[it.id] = it }
    }

    fun all(): List<CatalogRoute> = routes.values.sortedBy { it.origin }

    fun byId(id: UUID): CatalogRoute? = routes[id]

    /** Applies a reservation event to the advisory availability (idempotent per reservationId). */
    fun applyReservation(reservationId: UUID, routeId: UUID, seats: Int) {
        if (!seenReservations.add(reservationId)) return // redelivery → no-op
        routes.computeIfPresent(routeId) { _, r ->
            r.copy(availableSeats = (r.availableSeats - seats).coerceAtLeast(0))
        }
    }
}
