package com.eurotransit.inventory.repository

import com.eurotransit.inventory.model.ProcessedEvent
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ProcessedEventRepository : CoroutineCrudRepository<ProcessedEvent, String> {

    /** Check if an event has already been processed (Kafka consumer dedup). */
    suspend fun existsByEventId(eventId: String): Boolean
}
