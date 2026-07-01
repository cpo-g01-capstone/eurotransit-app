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
 * Transaction boundary is managed by the caller (InventoryKafkaConsumer)
 * to include the processed_events dedup record in the same transaction.
 */
@Service
class InventoryService(
    private val routeRepository: RouteRepository,
    private val reservationRepository: ReservationRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Reserves seats for an order. Returns the reservation and the route price.
     *
     * @return Pair(reservation, totalAmount) or null if the route doesn't exist
     * @throws InsufficientSeatsException if not enough seats available
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

        // 2. Load route for optimistic locking version
        val route = routeRepository.findById(routeId) ?: run {
            logger.warn("Route {} not found", routeId)
            return null
        }

        // 3. Atomic seat reservation (optimistic concurrency)
        val updated = routeRepository.reserveSeats(routeId, seats, route.version)
        if (updated == 0) {
            logger.warn(
                "Insufficient seats or version conflict for routeId={}, requested={}, available={}",
                routeId, seats, route.availableSeats
            )
            throw InsufficientSeatsException(routeId, seats, route.availableSeats)
        }

        // 4. Create reservation record
        val reservation = reservationRepository.save(
            Reservation(
                orderId = orderId,
                routeId = routeId,
                seats = seats
            )
        )

        val totalAmount = route.price.multiply(BigDecimal(seats))
        logger.info(
            "Reserved {} seats on route {} for order {} (total: {})",
            seats, routeId, orderId, totalAmount
        )
        return Pair(reservation, totalAmount)
    }
}

class InsufficientSeatsException(
    val routeId: UUID,
    val requested: Int,
    val available: Int
) : RuntimeException(
    "Insufficient seats on route $routeId: requested=$requested, available=$available"
)
