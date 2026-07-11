package com.eurotransit.orders.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("orders")
data class Order(
    @Id
    val id: UUID = UUID.randomUUID(),
    val customerId: String,
    val routeId: String,
    val seatClass: String,
    val quantity: Int = 1,
    val totalAmount: BigDecimal,
    val status: OrderStatus = OrderStatus.DRAFT,
    val failureReason: String? = null,
    
    @Version
    val version: Int = 0,
    
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class OrderStatus {
    DRAFT,
    RESERVED,
    PAID,
    CONFIRMED,
    FAILED
}
