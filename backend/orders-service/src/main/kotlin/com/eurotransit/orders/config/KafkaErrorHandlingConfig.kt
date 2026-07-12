package com.eurotransit.orders.config

import com.eurotransit.orders.event.OrderFailedEvent
import com.eurotransit.orders.kafka.OrderKafkaProducer
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.repository.OrderRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.ConsumerRecordRecoverer
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
 * When retries are exhausted, the recoverer applies the seat-release compensation:
 * the order is marked FAILED (from RESERVED, or DRAFT if it never got that far)
 * and an `order-failed` event is published — Inventory consumes it and releases
 * any RESERVED seats for the order. This closes the ADR 0005 known gap.
 *
 * The publish is GUARDED (agent-log case 24, found live in CE-1 run 3): an
 * exhausted record does NOT imply the order failed — the order may have reached
 * CONFIRMED through a competing path (e.g. an authorize that succeeded during a
 * breaker HALF_OPEN probe while this record's redeliveries were still failing).
 * Compensating in that state would release seats that belong to a confirmed
 * order. So: publish only if the FAILED transition took effect now, or the order
 * is already FAILED (a replay after a recoverer crash between transition and
 * publish — the at-least-once argument stays valid for genuine failures).
 * Otherwise: log loudly + count, never compensate. Declared bound (mirror race):
 * if the recoverer marks FAILED and a `payment-authorized` lands a moment later,
 * that consumer's conditional RESERVED→CONFIRMED is a no-op — the order stays
 * FAILED with an AUTHORIZED intent on the Payments side; the demo PSP never
 * captures, a real one would need a void/refund step here.
 *
 * (Inventory's recoverer needs no such guard: its exhaustions come from
 * order-placed — an order whose reservation never succeeded cannot be CONFIRMED.)
 *
 * Boot wires a single CommonErrorHandler bean into the default listener factory,
 * so this applies to ALL Orders @KafkaListeners — all of them are idempotent, so
 * blanket redelivery is safe.
 */
@Configuration
class KafkaErrorHandlingConfig {

    @Bean
    fun kafkaErrorHandler(
        orderRepository: OrderRepository,
        orderKafkaProducer: OrderKafkaProducer,
        meterRegistry: MeterRegistry,
    ): DefaultErrorHandler {
        val backOff = ExponentialBackOff(1_000L, 2.0).apply {
            maxInterval = 30_000L
            maxAttempts = 6
        }
        val recoverer = OrderFailureRecoverer(
            orderRepository = orderRepository,
            publishOrderFailed = orderKafkaProducer::sendOrderFailed,
            meterRegistry = meterRegistry,
        )
        return DefaultErrorHandler(recoverer, backOff)
    }
}

/**
 * The exhaustion recoverer, extracted from the bean lambda so the compensation
 * semantics are integration-testable against a real database (the case-17 lesson:
 * money-path behaviour needs tests that hit the real write path, not mocks).
 */
class OrderFailureRecoverer(
    private val orderRepository: OrderRepository,
    private val publishOrderFailed: (OrderFailedEvent) -> Unit,
    meterRegistry: MeterRegistry,
) : ConsumerRecordRecoverer {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Fires when the case-24 guard declines to compensate — dashboard-visible. */
    private val compensationDeclined: Counter = Counter
        .builder("orders_compensation_declined_total")
        .description(
            "Redelivery exhaustions where the order was already in a terminal " +
                "SUCCESS state — order-failed NOT published (agent-log case 24 guard)",
        )
        .register(meterRegistry)

    override fun accept(record: ConsumerRecord<*, *>, ex: Exception) {
        val key = record.key()?.toString()
        logger.error(
            "Redelivery exhausted for topic={} key={} — applying the failure transition.",
            record.topic(), key, ex,
        )
        val orderId = runCatching { UUID.fromString(key) }.getOrNull() ?: return
        runBlocking { // recoverer runs on the container thread (blocking by nature)
            val fromReserved = orderRepository.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.RESERVED)
            val fromDraft =
                if (fromReserved == 0) orderRepository.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.DRAFT)
                else 0

            // Feedback-loop guard: this service also CONSUMES order-failed
            // (OrderFailedConsumer). If the exhausted record came from that
            // topic, republishing would produce order-failed -> fail ->
            // order-failed... for as long as the outage lasts. The transition
            // above already did the consumer's job (a DB failure there throws
            // first, so the container keeps seeking instead) — nothing to emit.
            if (record.topic() == OrderKafkaProducer.TOPIC_ORDER_FAILED) return@runBlocking

            // Compensation guard (agent-log case 24): see the class doc above.
            val shouldCompensate = fromReserved + fromDraft > 0 ||
                orderRepository.findStatusById(orderId) == OrderStatus.FAILED
            if (shouldCompensate) {
                publishOrderFailed(
                    OrderFailedEvent(
                        orderId = orderId,
                        reason = ex.cause?.message ?: ex.message ?: "redelivery exhausted",
                    ),
                )
            } else {
                compensationDeclined.increment()
                logger.error(
                    "Redelivery exhausted for order {} but it is in a terminal SUCCESS state — " +
                        "NOT publishing order-failed (case 24 guard: its seats belong to a confirmed order).",
                    orderId,
                )
            }
        }
    }
}
