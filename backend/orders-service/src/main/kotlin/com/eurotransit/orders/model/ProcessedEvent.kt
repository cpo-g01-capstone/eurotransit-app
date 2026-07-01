package com.eurotransit.orders.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("processed_events")
data class ProcessedEvent(
    @Id @Column("event_id") val eventId: String,
    val processedAt: Instant = Instant.now()
)
