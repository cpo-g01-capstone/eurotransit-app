package com.eurotransit.payments.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.ReactorContext
import kotlin.coroutines.coroutineContext

/**
 * Bridges the WebFlux request Observation across coroutine suspension points.
 *
 * WebFlux stores the server-request Observation in the Reactor context, while
 * KafkaTemplate's observation support reads a ThreadLocal. A coroutine resumes
 * on arbitrary threads after a suspension point, so the ThreadLocal is gone
 * there and a `kafkaTemplate.send` roots a brand-new trace instead of joining
 * the request trace. The Reactor context, however, travels WITH the coroutine
 * (kotlinx-coroutines-reactor exposes it as [ReactorContext]), so it is the
 * reliable source. Same fix as the orders-service copy of this file.
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
