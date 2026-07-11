package com.eurotransit.inventory.service

import com.eurotransit.inventory.model.Reservation
import com.eurotransit.inventory.repository.ReservationRepository
import com.eurotransit.inventory.repository.RouteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/**
 * Core inventory logic: atomic seat reservation with idempotency.
 *
 * Idempotency is guaranteed at two levels:
 * 1. Application level: check for existing reservation by (orderId, routeId)
 * 2. Database level: unique index on reservations(order_id, route_id)
 *
 * Concurrency safety is guaranteed by optimistic locking with bounded retry:
 * - The UPDATE statement checks both `available_seats >= :seats` AND `version = :expectedVersion`
 * - On version conflict (another transaction won the race) the service re-reads
 *   the route and retries up to [MAX_RETRIES] times
 * - The atomic SQL WHERE clause at the PostgreSQL level ensures that two customers
 *   can NEVER buy the last seat — only one UPDATE can succeed per row-level lock
 *
 * Transaction boundary is managed by the caller (InventoryKafkaConsumer)
 * to include the processed_events dedup record in the same transaction.
 */
@Service
class InventoryService(
    private val routeRepository: RouteRepository,
    private val reservationRepository: ReservationRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Maximum number of optimistic lock retries before giving up. */
        const val MAX_RETRIES = 3
    }

    /**
     * Reserves seats for an order with optimistic locking and bounded retry.
     *
     * @return Pair(reservation, totalAmount) or null if the route doesn't exist
     * @throws InsufficientSeatsException if not enough seats available
     * @throws VersionConflictException if all retries are exhausted due to contention
     */
    suspend fun reserveSeats(
        orderId: UUID,
        routeId: UUID,
        seats: Int
    ): Pair<Reservation, BigDecimal>? {
        // 1. Idempotency: check if reservation already exists
        val existing = reservationRepository.findByOrderIdAndRouteId(orderId, routeId)
        if (existing != null) {
            logger.info(
                "Reservation already exists for orderId={}, routeId={} — returning existing",
                orderId, routeId
            )
            val route = routeRepository.findById(routeId)
                ?: return null
            return Pair(existing, route.price.multiply(BigDecimal(seats)))
        }

        // 2. Optimistic lock with bounded retry
        repeat(MAX_RETRIES) { attempt ->
            val route = routeRepository.findById(routeId) ?: run {
                logger.warn("Route {} not found", routeId)
                return null
            }

            // Fast-fail: no point retrying if seats are genuinely exhausted
            if (route.availableSeats < seats) {
                throw InsufficientSeatsException(routeId, seats, route.availableSeats)
            }

            // Atomic UPDATE: decrements available_seats only if enough seats
            // are available AND the version matches. PostgreSQL acquires a
            // row-level lock during the UPDATE — only one concurrent caller
            // can succeed for the same row.
            val updated = routeRepository.reserveSeats(routeId, seats, route.version)
            if (updated == 1) {
                // SUCCESS — seats reserved atomically
                val reservation = reservationRepository.save(
                    Reservation(
                        orderId = orderId,
                        routeId = routeId,
                        seats = seats
                    )
                )
                val totalAmount = route.price.multiply(BigDecimal(seats))
                logger.info(
                    "Reserved {} seats on route {} for order {} (total: {}, version: {}→{})",
                    seats, routeId, orderId, totalAmount, route.version, route.version + 1
                )
                return Pair(reservation, totalAmount)
            }

            // UPDATE returned 0 — version conflict (another transaction won the race).
            // Re-read and retry; the loop guard enforces the MAX_RETRIES bound.
            logger.warn(
                "Version conflict on route {} (attempt {}/{}, version was {}), retrying",
                routeId, attempt + 1, MAX_RETRIES, route.version
            )
        }

        // 3. All retries exhausted — re-read to provide a meaningful error
        val finalRoute = routeRepository.findById(routeId)
        if (finalRoute != null && finalRoute.availableSeats < seats) {
            // Seats genuinely sold out during contention
            throw InsufficientSeatsException(routeId, seats, finalRoute.availableSeats)
        }
        // Extreme contention — seats exist but we lost every race
        throw VersionConflictException(routeId, MAX_RETRIES)
    }
}

/** Thrown when the route does not have enough available seats. */
class InsufficientSeatsException(
    val routeId: UUID,
    val requested: Int,
    val available: Int
) : RuntimeException(
    "Insufficient seats on route $routeId: requested=$requested, available=$available"
)

/** Thrown when all optimistic lock retries are exhausted due to extreme contention. */
class VersionConflictException(
    val routeId: UUID,
    val attempts: Int
) : RuntimeException(
    "Version conflict on route $routeId: exhausted $attempts retries due to contention"
)
