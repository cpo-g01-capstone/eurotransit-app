package com.eurotransit.inventory.repository

import com.eurotransit.inventory.model.Route
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface RouteRepository : CoroutineCrudRepository<Route, UUID> {

    /**
     * Atomic seat reservation with optimistic concurrency.
     * Decrements available_seats only if enough seats are available AND the version matches.
     * Returns 0 if insufficient seats or version conflict (retry needed).
     */
    @Modifying
    @Query(
        """
        UPDATE routes 
        SET available_seats = available_seats - :seats, 
            version = version + 1
        WHERE id = :routeId 
          AND available_seats >= :seats 
          AND version = :expectedVersion
        """
    )
    suspend fun reserveSeats(routeId: UUID, seats: Int, expectedVersion: Int): Int

    /**
     * Pure atomic seat decrement — no version check.
     * The WHERE clause alone guarantees the never-oversell invariant:
     * PostgreSQL acquires a row-level lock during UPDATE, so only one
     * concurrent caller can succeed when available_seats drops to zero.
     */
    @Modifying
    @Query(
        """
        UPDATE routes
        SET available_seats = available_seats - :seats
        WHERE id = :routeId
          AND available_seats >= :seats
        """
    )
    suspend fun atomicReserveSeats(routeId: UUID, seats: Int): Int

    /**
     * Seat-release compensation (D4): atomic give-back of released seats.
     * The `available_seats + :seats <= total_seats` guard protects invariant I1
     * (never more available than total) — 0 rows back means the caller is trying
     * to release seats that were never (still) reserved: log it, don't corrupt.
     * The caller must have won the RESERVED -> RELEASED reservation transition
     * first, which makes each release exactly-once.
     */
    @Modifying
    @Query(
        """
        UPDATE routes
        SET available_seats = available_seats + :seats,
            version = version + 1
        WHERE id = :routeId
          AND available_seats + :seats <= total_seats
        """
    )
    suspend fun releaseSeats(routeId: UUID, seats: Int): Int
}
