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
 *  - no database on purpose: state is disposable. On restart the cache is
 *    HYDRATED from an Inventory snapshot (RouteCacheHydrator) and then kept
 *    warm by events from the current offset onward — stale-then-convergent,
 *    exactly AP/EL. (The original replay-from-earliest scheme re-derived the
 *    state from event history and diverged permanently whenever seats changed
 *    outside the stream, e.g. a SQL reseed — issue #31.)
 *
 * The seed below is a FALLBACK only: it mirrors inventory's
 * V2__seed_demo_routes.sql (same deterministic ids) so browsing works before
 * the first successful hydration — including when Inventory is down at
 * startup, which browse must survive (CE-1).
 */
@Component
class RouteCache {

    private val routes = ConcurrentHashMap<UUID, CatalogRoute>()

    // Best-effort dedup of event redeliveries (at-least-once): enough to avoid
    // double-decrement within a pod's lifetime; after a restart the snapshot
    // hydration re-baselines the state, so persistence is unnecessary.
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

    /**
     * Replaces the whole cache with an authoritative Inventory snapshot.
     * Replace-all, not merge: Inventory is the source of truth, so routes it
     * no longer knows must disappear here too (retainAll-then-putAll keeps
     * `all()` non-empty throughout — no flash of an empty catalog).
     * `seenReservations` is kept: any id already seen is by definition an
     * event whose effect the snapshot includes (Inventory commits before
     * publishing), so a later redelivery must still be a no-op.
     */
    fun hydrate(snapshot: List<CatalogRoute>) {
        val byId = snapshot.associateBy { it.id }
        routes.keys.retainAll(byId.keys)
        routes.putAll(byId)
    }
}
