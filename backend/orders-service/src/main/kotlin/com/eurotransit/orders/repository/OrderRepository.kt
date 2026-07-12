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

    /**
     * Current status only — used by the recoverer's compensation guard
     * (agent-log case 24) to distinguish "already FAILED" (replay after a
     * recoverer crash: still publish, at-least-once) from "reached a terminal
     * SUCCESS state" (never publish: the seats belong to a confirmed order).
     */
    @Query("SELECT status FROM orders WHERE id = :id")
    suspend fun findStatusById(id: UUID): OrderStatus?
}
