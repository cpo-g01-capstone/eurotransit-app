package com.eurotransit.inventory.repository

import com.eurotransit.inventory.model.Reservation
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface ReservationRepository : CoroutineCrudRepository<Reservation, UUID> {

    /** Check for existing reservation (idempotency at DB level via unique index). */
    suspend fun findByOrderIdAndRouteId(orderId: UUID, routeId: UUID): Reservation?
}
