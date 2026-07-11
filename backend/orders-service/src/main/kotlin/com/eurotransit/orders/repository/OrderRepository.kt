package com.eurotransit.orders.repository

import com.eurotransit.orders.model.Order
import com.eurotransit.orders.model.OrderStatus
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

interface OrderRepository : CoroutineCrudRepository<Order, UUID> {

    /**
     * Optimistic state transition: only updates if the current status matches
     * [expectedStatus]. Returns the number of rows affected (0 = conflict).
     */
    @Modifying
    @Query(
        """
        UPDATE orders 
        SET status = :newStatus, updated_at = :now 
        WHERE id = :id AND status = :expectedStatus
        """
    )
    suspend fun updateStatus(
        id: UUID,
        newStatus: OrderStatus,
        expectedStatus: OrderStatus,
        now: Instant = Instant.now()
    ): Int
}
