package com.eurotransit.orders.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

enum class OrderStatus {
    // PAID removed (final-audit #6): pre-D1 residue from the async payment
    // stage design — with the synchronous authorize (ADR 0018) the real
    // transition is RESERVED -> CONFIRMED.
    DRAFT, RESERVED, CONFIRMED, FAILED
}

@Table("orders")
data class Order(
    @Id val id: UUID,
    val status: OrderStatus = OrderStatus.DRAFT,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
