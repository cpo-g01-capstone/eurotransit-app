package com.eurotransit.orders.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.ReactorContext
import kotlin.coroutines.coroutineContext

/**
 * Bridges the WebFlux request Observation across coroutine suspension points.
 *
 * WebFlux stores the server-request Observation in the Reactor context, while
 * `tracer.currentSpan()` and KafkaTemplate's observation support read a
 * ThreadLocal. A coroutine resumes on arbitrary threads after a suspension
 * point, so the ThreadLocal is gone there: `currentSpan()` returns null and a
 * `kafkaTemplate.send` roots a brand-new trace instead of joining the request
 * trace. The Reactor context, however, travels WITH the coroutine
 * (kotlinx-coroutines-reactor exposes it as [ReactorContext]), so it is the
 * reliable source. The runBlocking Kafka-listener bridge (app ADR-004) is NOT
 * affected: runBlocking keeps every continuation on the listener thread, so
 * the container's ThreadLocal observation scope stays valid there.
 */
suspend fun currentRequestObservation(): Observation? =
    coroutineContext[ReactorContext]?.context
        ?.getOrDefault<Observation?>(ObservationThreadLocalAccessor.KEY, null)

/**
 * Runs [block] with the request Observation restored as the current
 * ThreadLocal observation, so observation-aware infrastructure called inside
 * (e.g. `kafkaTemplate.send`) parents its span to the request trace. No-op
 * outside a request coroutine, where the ThreadLocal path already works.
 * [block] must not suspend — the restored scope is thread-bound.
 */
suspend fun <T> withRequestObservation(block: () -> T): T {
    val observation = currentRequestObservation() ?: return block()
    return observation.openScope().use { block() }
}
