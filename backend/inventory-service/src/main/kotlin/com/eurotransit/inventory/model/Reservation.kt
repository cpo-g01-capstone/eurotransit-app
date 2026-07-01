package com.eurotransit.inventory.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("reservations")
data class Reservation(
    @Id val id: UUID = UUID.randomUUID(),
    val orderId: UUID,
    val routeId: UUID,
    val seats: Int,
    val status: String = "RESERVED",
    val createdAt: Instant = Instant.now()
)
