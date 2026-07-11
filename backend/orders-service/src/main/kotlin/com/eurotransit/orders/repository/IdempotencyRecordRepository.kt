package com.eurotransit.orders.repository

import com.eurotransit.orders.model.IdempotencyRecord
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface IdempotencyRecordRepository : CoroutineCrudRepository<IdempotencyRecord, String> {

    /** Find a cached response for a previously processed HTTP request. */
    suspend fun findByIdempotencyKey(idempotencyKey: String): IdempotencyRecord?

    /**
     * Insert-first creation (docs/design/idempotency.md): a client-assigned
     * @Id makes `save()` issue an UPDATE for a row that does not exist.
     * The JSONB cast is required — R2DBC binds :responsePayload as text.
     */
    @Modifying
    @Query(
        """
        INSERT INTO idempotency_records (idempotency_key, response_payload, created_at)
        VALUES (:idempotencyKey, CAST(:responsePayload AS jsonb), :createdAt)
        """
    )
    suspend fun insert(
        idempotencyKey: String,
        responsePayload: String,
        createdAt: Instant = Instant.now()
    ): Int
}
