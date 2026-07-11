package com.eurotransit.notifications.listener

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.service.NotificationService
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Terminal consumer of the money path (ADR-001).
 *
 * Two deliberate Spring-Kafka choices (both verified by the integration tests):
 *  - **Raw `ConsumerRecord` parameter** instead of a typed `OrderConfirmedEvent` payload: with a
 *    non-`suspend` handler, Spring Kafka's typed-payload conversion yields a `KafkaNull` for an
 *    already-deserialized value, so the record is taken raw and its value read directly.
 *  - **`runBlocking`** to bridge to the suspending [NotificationService]: a `suspend` @KafkaListener
 *    consumes fine but its exceptions are NOT propagated to the container's DefaultErrorHandler, so
 *    retries/DLT never fire. Blocking the consumer thread here is correct — with AckMode.RECORD the
 *    offset must not advance until processing completes, and the thrown exception must reach the
 *    error handler (retry -> DLT for send failures; block-and-lag for transient DB errors). This is
 *    the one place the "no runBlocking" guideline does not apply: the consumer thread is a dedicated
 *    blocking poll loop, not a reactive/coroutine context.
 */
@Component
class OrderConfirmedListener(private val service: NotificationService) {

    @KafkaListener(
        topics = ["order-confirmed"],
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun onOrderConfirmed(record: ConsumerRecord<String, OrderConfirmedEvent?>) {
        val event = record.value() ?: return // null value = tombstone, nothing to notify
        runBlocking { service.handle(event) }
    }
}
