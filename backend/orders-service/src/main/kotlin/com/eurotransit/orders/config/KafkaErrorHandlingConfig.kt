package com.eurotransit.orders.config

import com.eurotransit.orders.event.OrderFailedEvent
import com.eurotransit.orders.kafka.OrderKafkaProducer
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
 * When retries are exhausted, the recoverer applies the compensation (D4):
 * the order is marked FAILED (from RESERVED, or DRAFT if it never got that far)
 * and an `order-failed` event is published — Inventory consumes it and releases
 * any RESERVED seats for the order (idempotently, so publishing on every
 * exhaustion — even a replayed one — is safe). This closes the ADR 0005 known gap.
 *
 * Boot wires a single CommonErrorHandler bean into the default listener factory,
 * so this applies to ALL Orders @KafkaListeners — all of them are idempotent, so
 * blanket redelivery is safe.
 */
@Configuration
class KafkaErrorHandlingConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun kafkaErrorHandler(
        orderRepository: OrderRepository,
        orderKafkaProducer: OrderKafkaProducer,
    ): DefaultErrorHandler {
        val backOff = ExponentialBackOff(1_000L, 2.0).apply {
            maxInterval = 30_000L
            maxAttempts = 6
        }

        return DefaultErrorHandler({ record, ex ->
            val key = record.key()?.toString()
            logger.error(
                "Redelivery exhausted for topic={} key={} — marking order FAILED and publishing order-failed (D4).",
                record.topic(), key, ex,
            )
            val orderId = runCatching { UUID.fromString(key) }.getOrNull() ?: return@DefaultErrorHandler
            runBlocking { // recoverer runs on the container thread (blocking by nature)
                val fromReserved = orderRepository.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.RESERVED)
                if (fromReserved == 0) {
                    orderRepository.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.DRAFT)
                }
            }
            // Feedback-loop guard: this service also CONSUMES order-failed
            // (OrderFailedConsumer). If the exhausted record came from that
            // topic, republishing would produce order-failed -> fail ->
            // order-failed... for as long as the outage lasts. The transition
            // above already did the consumer's job (a DB failure there throws
            // first, so the container keeps seeking instead) — nothing to emit.
            if (record.topic() == OrderKafkaProducer.TOPIC_ORDER_FAILED) return@DefaultErrorHandler
            // Otherwise always publish, even on a replayed exhaustion or if the
            // order was already FAILED: the Inventory release is a conditional
            // no-op on replay, and at-least-once beats silently keeping seats locked.
            orderKafkaProducer.sendOrderFailed(
                OrderFailedEvent(orderId = orderId, reason = ex.cause?.message ?: ex.message ?: "redelivery exhausted"),
            )
        }, backOff)
    }
}
