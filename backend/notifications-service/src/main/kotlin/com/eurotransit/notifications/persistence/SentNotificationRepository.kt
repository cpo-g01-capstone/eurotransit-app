package com.eurotransit.notifications.persistence

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SentNotificationRepository : CoroutineCrudRepository<SentNotification, String> {

    /** Atomic insert-if-absent. Returns 1 when newly claimed, 0 when a row already exists. */
    @Modifying
    @Query(
        """
        INSERT INTO sent_notifications (order_id, status)
        VALUES (:orderId, 'PENDING')
        ON CONFLICT (order_id) DO NOTHING
        """
    )
    suspend fun claim(orderId: String): Long

    @Query("SELECT status FROM sent_notifications WHERE order_id = :orderId")
    suspend fun findStatus(orderId: String): String?

    @Modifying
    @Query("UPDATE sent_notifications SET status = :status, updated_at = NOW() WHERE order_id = :orderId")
    suspend fun updateStatus(orderId: String, status: String): Long
}
