package com.eurotransit.orders.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

enum class OrderStatus {
    DRAFT, RESERVED, PAID, CONFIRMED, FAILED
}

@Table("orders")
data class Order(
    @Id val id: UUID,
    val status: OrderStatus = OrderStatus.DRAFT,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
