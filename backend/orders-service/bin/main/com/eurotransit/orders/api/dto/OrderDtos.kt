package com.eurotransit.orders.api.dto

import java.math.BigDecimal
import java.util.UUID

data class OrderRequest(
    val customerId: String,
    val routeId: String,
    val seatClass: String,
    val quantity: Int = 1,
    val totalAmount: BigDecimal
)

data class OrderResponse(
    val orderId: UUID,
    val status: String,
    val message: String
)
