package com.eurotransit.inventory.config

import com.eurotransit.inventory.event.OrderFailedEvent
import com.eurotransit.inventory.kafka.InventoryKafkaProducer
import com.eurotransit.inventory.service.InsufficientSeatsException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import java.util.UUID

/**
 * Bounded, backed-off redelivery for the Inventory consumers — added by the
 * adversarial audit (#19): until now Inventory had NO error handler at all, so a
 * handler exception meant a silent drop (and with the old suspend listener it
 * never even left the coroutine).
 *
 * Two failure classes, two behaviours:
 * - **InsufficientSeats — non-retryable.** Sold-out is a fact, not a glitch:
 *   retrying cannot conjure seats. The recoverer runs immediately and publishes
 *   `order-failed(SOLD_OUT)`; Orders consumes it and marks the order FAILED —
 *   this closes the dead sold-out path (order no longer stuck in DRAFT).
 * - **Everything else (DB down, transient)** — exponential redelivery
 *   (1s → 30s, 6 retries, same policy as Orders); on exhaustion the recoverer
 *   publishes `order-failed` with the failure reason.
 *
 * The recoverer's publish is at-least-once by design: both consumers of
 * order-failed (Orders: conditional →FAILED transition; Inventory: conditional
 * RESERVED→RELEASED) are no-ops on replay.
 */
@Configuration
class KafkaErrorHandlingConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun kafkaErrorHandler(inventoryKafkaProducer: InventoryKafkaProducer): DefaultErrorHandler {
        val backOff = ExponentialBackOff(1_000L, 2.0).apply {
            maxInterval = 30_000L
            maxAttempts = 6
        }

        val handler = DefaultErrorHandler({ record, ex ->
            val key = record.key()?.toString()
            // Feedback-loop guard: this service also CONSUMES order-failed (the
            // seat-release compensation). Republishing on ITS exhaustion would
            // produce order-failed -> fail -> order-failed... for as long as the
            // outage lasts; dropping it would leave seats locked. Rethrow instead:
            // recovery "fails", the container keeps seeking the record, and the
            // release lands as soon as the DB is back (bounded backpressure on a
            // low-traffic compensation topic, no loss, no loop).
            if (record.topic() == InventoryKafkaProducer.TOPIC_ORDER_FAILED) {
                logger.error("order-failed release exhausted for key={} — keep seeking, not republishing.", key)
                throw ex
            }
            val cause = ex.cause ?: ex
            val soldOut = cause is InsufficientSeatsException
            logger.error(
                "Recovering after {} for topic={} key={} — publishing order-failed.",
                if (soldOut) "sold-out (non-retryable)" else "redelivery exhaustion",
                record.topic(), key, ex,
            )
            val orderId = runCatching { UUID.fromString(key) }.getOrNull() ?: return@DefaultErrorHandler
            inventoryKafkaProducer.sendOrderFailed(
                OrderFailedEvent(
                    orderId = orderId,
                    reason = if (soldOut) "SOLD_OUT" else (cause.message ?: "redelivery exhausted"),
                ),
            )
        }, backOff)

        // Sold-out is deterministic: skip the retry ladder, recover immediately.
        handler.addNotRetryableExceptions(InsufficientSeatsException::class.java)
        return handler
    }
}
