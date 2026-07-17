package com.eurotransit.orders.observability

import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Adds order correlation to the active trace.
 *
 * `order.id` is deliberately a trace span attribute, not a Prometheus metric
 * label: order IDs are high-cardinality and belong in Tempo search, where one
 * matching span is enough to retrieve the full HTTP + Kafka trace.
 *
 * In request coroutines the attribute is applied through the request
 * Observation from the coroutine's Reactor context, NOT via
 * `tracer.currentSpan()`: the tag sites sit past suspension points, where the
 * ThreadLocal span is already gone and `currentSpan()` returns null — the tag
 * silently vanished and order traces were unsearchable in Grafana. The
 * high-cardinality key value is copied onto the server span when the
 * observation stops. This is a pure metadata add on the observation — no
 * scope is opened, so it is safe regardless of what other threads do with the
 * request observation (unlike the scope-reopening approach reverted in #42).
 */
@Component
class OrderTraceTagger(private val tracer: Tracer) {

    suspend fun tag(orderId: UUID) {
        val observation = currentRequestObservation()
        if (observation != null) {
            observation.highCardinalityKeyValue(ORDER_ID_ATTRIBUTE, orderId.toString())
        } else {
            tracer.currentSpan()?.tag(ORDER_ID_ATTRIBUTE, orderId.toString())
        }
    }

    /**
     * For @KafkaListener threads (before the runBlocking bridge), where the
     * container's listener observation scope is ThreadLocal-current: tags the
     * consumer span so every async stage of an order is findable by
     * `span.order.id` even if a producer/consumer trace link ever breaks.
     */
    fun tagConsumerSpan(orderId: UUID) {
        tracer.currentSpan()?.tag(ORDER_ID_ATTRIBUTE, orderId.toString())
    }

    companion object {
        const val ORDER_ID_ATTRIBUTE = "order.id"
    }
}
