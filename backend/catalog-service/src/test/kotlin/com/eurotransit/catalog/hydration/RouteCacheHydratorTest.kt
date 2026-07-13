package com.eurotransit.catalog.hydration

import com.eurotransit.catalog.cache.CatalogRoute
import com.eurotransit.catalog.cache.RouteCache
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RouteCacheHydratorTest {

    private val turinMilan = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun snapshotRoute(available: Int) = CatalogRoute(
        id = turinMilan, origin = "Turin", destination = "Milan",
        departureTime = Instant.now().plus(7, ChronoUnit.DAYS),
        totalSeats = 100, availableSeats = available, price = BigDecimal("19.90"),
    )

    @Test
    fun `hydrates the cache from the snapshot source`() = runBlocking {
        val cache = RouteCache()
        val hydrator = RouteCacheHydrator({ listOf(snapshotRoute(available = 42)) }, cache)

        hydrator.hydrateUntilSuccess()

        assertEquals(42, cache.byId(turinMilan)!!.availableSeats)
    }

    @Test
    fun `retries until Inventory answers - startup while inventory pods are down (CE-2)`() = runBlocking {
        val cache = RouteCache()
        val calls = AtomicInteger()
        val flakySource = InventorySnapshotSource {
            if (calls.incrementAndGet() < 3) error("connection refused")
            listOf(snapshotRoute(available = 100))
        }
        val hydrator = RouteCacheHydrator(flakySource, cache)

        hydrator.hydrateUntilSuccess(initialBackoff = Duration.ofMillis(1), maxBackoff = Duration.ofMillis(2))

        assertEquals(3, calls.get())
        assertEquals(100, cache.byId(turinMilan)!!.availableSeats)
    }

    @Test
    fun `until hydration succeeds the seed fallback keeps serving`() = runBlocking {
        val cache = RouteCache()
        // No hydrator run at all — the pre-hydration state must be browsable (CE-1).
        assertEquals(100, cache.byId(turinMilan)!!.availableSeats)
        assertEquals(2, cache.all().size)
    }
}
