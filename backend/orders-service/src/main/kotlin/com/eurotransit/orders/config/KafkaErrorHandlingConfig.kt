package com.eurotransit.orders.config

import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.repository.OrderRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import java.util.UUID

/**
 * Bounded, backed-off redelivery for the Orders consumers — the second half of the
 * ADR 0018 fallback.
 *
 * When a handler throws (e.g. Payments unavailable, breaker OPEN), the container
 * seeks back and redelivers with exponential backoff (1s → 30s, 6 retries): this
 * IS the "payment queued for retry" behaviour — spaced attempts instead of a hot
 * loop, while the order sits safely in RESERVED. Every redelivery is idempotent
 * by construction (conditional state transitions + Idempotency-Key on authorize).
 *
 * When retries are exhausted, the recoverer applies the compensation stub:
 * the order is marked FAILED (from RESERVED, or DRAFT if it never got that far)
 * and the failure is logged at ERROR — the symptom-based alerts pick up the 5xx/
 * lag signals. Publishing a seat-release compensation event to Inventory is the
 * documented follow-up (ADR-005 known gap; needs the release-topic decision D4).
 *
 * Boot wires a single CommonErrorHandler bean into the default listener factory,
 * so this applies to ALL Orders @KafkaListeners — all of them are idempotent, so
 * blanket redelivery is safe.
 */
@Configuration
class KafkaErrorHandlingConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun kafkaErrorHandler(orderRepository: OrderRepository): DefaultErrorHandler {
        val backOff = ExponentialBackOff(1_000L, 2.0).apply {
            maxInterval = 30_000L
            maxAttempts = 6
        }

        return DefaultErrorHandler({ record, ex ->
            val key = record.key()?.toString()
            logger.error(
                "Redelivery exhausted for topic={} key={} — marking order FAILED. " +
                    "Seat-release compensation event is a documented follow-up (ADR-005 / D4).",
                record.topic(), key, ex,
            )
            val orderId = runCatching { UUID.fromString(key) }.getOrNull() ?: return@DefaultErrorHandler
            runBlocking { // recoverer runs on the container thread (blocking by nature)
                val fromReserved = orderRepository.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.RESERVED)
                if (fromReserved == 0) {
                    orderRepository.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.DRAFT)
                }
            }
        }, backOff)
    }
}
