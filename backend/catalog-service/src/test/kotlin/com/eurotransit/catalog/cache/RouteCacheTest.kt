package com.eurotransit.catalog.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Regression for issue #31: the cache must converge to whatever an Inventory
 * snapshot says — including seat state created OUTSIDE the event stream (SQL
 * reseed) — instead of re-deriving state from event history.
 */
class RouteCacheTest {

    private val turinMilan = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun route(id: UUID, available: Int, total: Int = 100) = CatalogRoute(
        id = id, origin = "Turin", destination = "Milan",
        departureTime = Instant.now().plus(7, ChronoUnit.DAYS),
        totalSeats = total, availableSeats = available, price = BigDecimal("19.90"),
    )

    @Test
    fun `hydrate overrides decrements accumulated from events - the reseed scenario`() {
        val cache = RouteCache()
        // Historical reservations drain the seed route to 0 (what replay-from-earliest did).
        repeat(60) { cache.applyReservation(UUID.randomUUID(), turinMilan, 2) }
        assertEquals(0, cache.byId(turinMilan)!!.availableSeats)

        // `just seed-db` resets inventorydb to 100/100; the snapshot is authoritative.
        cache.hydrate(listOf(route(turinMilan, available = 100)))
        assertEquals(100, cache.byId(turinMilan)!!.availableSeats)
    }

    @Test
    fun `hydrate is replace-all - routes absent from the snapshot disappear`() {
        val cache = RouteCache()
        val onlyRoute = route(UUID.randomUUID(), available = 5)
        cache.hydrate(listOf(onlyRoute))

        assertEquals(listOf(onlyRoute.id), cache.all().map { it.id })
        assertNull(cache.byId(turinMilan), "seed fallback must not survive hydration")
    }

    @Test
    fun `events keep applying on top of the hydrated baseline`() {
        val cache = RouteCache()
        cache.hydrate(listOf(route(turinMilan, available = 100)))

        cache.applyReservation(UUID.randomUUID(), turinMilan, 3)
        assertEquals(97, cache.byId(turinMilan)!!.availableSeats)
    }

    @Test
    fun `reservation dedup survives hydration - a redelivery after hydrate stays a no-op`() {
        val cache = RouteCache()
        val reservationId = UUID.randomUUID()
        cache.applyReservation(reservationId, turinMilan, 2)

        // The snapshot already includes that reservation's effect (98 seats):
        // Inventory commits the DB update before publishing the event.
        cache.hydrate(listOf(route(turinMilan, available = 98)))

        cache.applyReservation(reservationId, turinMilan, 2) // at-least-once redelivery
        assertEquals(98, cache.byId(turinMilan)!!.availableSeats)
    }
}
