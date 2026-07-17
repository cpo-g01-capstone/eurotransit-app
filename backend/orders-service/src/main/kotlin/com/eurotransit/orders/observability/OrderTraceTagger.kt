package com.eurotransit.orders.observability

import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Adds order correlation to the active checkout trace.
 *
 * `order.id` is deliberately a trace span attribute, not a Prometheus metric
 * label: order IDs are high-cardinality and belong in Tempo search, where one
 * matching span is enough to retrieve the full HTTP + Kafka trace.
 */
@Component
class OrderTraceTagger(private val tracer: Tracer) {

    fun tag(orderId: UUID) {
        tracer.currentSpan()?.tag(ORDER_ID_ATTRIBUTE, orderId.toString())
    }

    companion object {
        const val ORDER_ID_ATTRIBUTE = "order.id"
    }
}
