package com.eurotransit.inventory.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("routes")
data class Route(
    @Id val id: UUID,
    val origin: String,
    val destination: String,
    val departureTime: Instant,
    val totalSeats: Int,
    val availableSeats: Int,
    val price: BigDecimal,
    @Version val version: Int = 0
)
