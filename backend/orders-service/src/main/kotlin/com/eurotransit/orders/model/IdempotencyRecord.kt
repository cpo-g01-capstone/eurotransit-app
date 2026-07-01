package com.eurotransit.orders.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("idempotency_records")
data class IdempotencyRecord(
    @Id @Column("idempotency_key") val idempotencyKey: String,
    val responsePayload: String? = null,
    val createdAt: Instant = Instant.now()
)
