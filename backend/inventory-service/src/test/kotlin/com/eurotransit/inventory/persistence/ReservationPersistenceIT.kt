package com.eurotransit.inventory.persistence

import com.eurotransit.inventory.AbstractIntegrationTest
import com.eurotransit.inventory.repository.ReservationRepository
import com.eurotransit.inventory.repository.RouteRepository
import com.eurotransit.inventory.service.InventoryService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Persistence round-trip for the atomic seat reservation against a real
 * PostgreSQL (Flyway schema + demo-route seed).
 *
 * Regression guard: `save()` on the pre-assigned Reservation id issued an
 * UPDATE for a row that does not exist yet (insert-first bug, see
 * OrderPersistenceIT in orders-service for the full story).
 */
class ReservationPersistenceIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var reservationRepository: ReservationRepository

    @Autowired
    lateinit var routeRepository: RouteRepository

    /** Seeded by V2__seed_demo_routes.sql — 100 seats, plenty for these tests. */
    private val bigRoute: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `reserveSeats persists the reservation row`() = runBlocking {
        val orderId = UUID.randomUUID()

        val result = inventoryService.reserveSeats(orderId, bigRoute, 1)

        assertNotNull(result, "reservation must succeed on a seeded route")
        val saved = reservationRepository.findByOrderIdAndRouteId(orderId, bigRoute)
        assertNotNull(saved, "the reservation row must exist after reserveSeats")
        assertEquals(1, saved!!.seats)
    }

    @Test
    fun `reserveSeats is idempotent for the same order and decrements seats once`() = runBlocking {
        val orderId = UUID.randomUUID()
        val seatsBefore = routeRepository.findById(bigRoute)!!.availableSeats

        val first = inventoryService.reserveSeats(orderId, bigRoute, 2)!!
        val second = inventoryService.reserveSeats(orderId, bigRoute, 2)!!

        assertEquals(first.first.id, second.first.id, "replay must return the existing reservation")
        val seatsAfter = routeRepository.findById(bigRoute)!!.availableSeats
        assertEquals(seatsBefore - 2, seatsAfter, "seats must be decremented exactly once")
    }
}
