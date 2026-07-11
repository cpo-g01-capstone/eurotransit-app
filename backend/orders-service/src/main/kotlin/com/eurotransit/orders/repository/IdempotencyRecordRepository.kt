package com.eurotransit.orders.repository

import com.eurotransit.orders.model.IdempotencyRecord
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface IdempotencyRecordRepository : CoroutineCrudRepository<IdempotencyRecord, String> {

    /** Find a cached response for a previously processed HTTP request. */
    suspend fun findByIdempotencyKey(idempotencyKey: String): IdempotencyRecord?
}
