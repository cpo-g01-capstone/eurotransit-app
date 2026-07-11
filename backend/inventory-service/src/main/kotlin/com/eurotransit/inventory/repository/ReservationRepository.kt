package com.eurotransit.inventory.repository

import com.eurotransit.inventory.model.Reservation
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface ReservationRepository : CoroutineCrudRepository<Reservation, UUID> {

    /** Check for existing reservation (idempotency at DB level via unique index). */
    suspend fun findByOrderIdAndRouteId(orderId: UUID, routeId: UUID): Reservation?

    /** All reservations for an order (order-failed release path). */
    fun findAllByOrderId(orderId: UUID): Flow<Reservation>

    /**
     * Conditional RESERVED -> RELEASED transition. Returns 0 on replay
     * (already RELEASED) — the caller must then NOT give the seats back again:
     * this row-level condition is what makes the release exactly-once under
     * at-least-once event delivery.
     */
    @Modifying
    @Query(
        """
        UPDATE reservations
        SET status = 'RELEASED'
        WHERE id = :id
          AND status = 'RESERVED'
        """
    )
    suspend fun markReleased(id: UUID): Int
}
