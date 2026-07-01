package com.eurotransit.payments.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("payment_intents")
data class PaymentIntent(
    @Id val id: UUID = UUID.randomUUID(),
    val orderId: UUID,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val status: String = "AUTHORIZED",
    val idempotencyKey: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
