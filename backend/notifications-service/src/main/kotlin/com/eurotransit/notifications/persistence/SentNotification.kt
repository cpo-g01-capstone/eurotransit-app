package com.eurotransit.notifications.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

@Table("sent_notifications")
data class SentNotification(
    @Id
    @Column("order_id")
    val orderId: String,
    val status: String,
    @Column("created_at") val createdAt: OffsetDateTime? = null,
    @Column("updated_at") val updatedAt: OffsetDateTime? = null,
)
