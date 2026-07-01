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
}
