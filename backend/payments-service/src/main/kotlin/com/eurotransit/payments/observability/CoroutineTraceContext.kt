package com.eurotransit.payments.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
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
 * reliable source. Same helper as the orders-service copy of this file.
 */
suspend fun currentRequestObservation(): Observation? =
    coroutineContext[ReactorContext]?.context
        ?.getOrDefault<Observation?>(ObservationThreadLocalAccessor.KEY, null)

/**
 * Runs [block] under a short-lived observation that is a CHILD of the request
 * Observation, so observation-aware infrastructure called inside (e.g.
 * `kafkaTemplate.send`) parents its span to the request trace. No-op outside a
 * request coroutine. [block] must not suspend — the opened scope is
 * thread-bound.
 *
 * Deliberately NOT `requestObservation.openScope()` (the approach reverted in
 * #42): the request Observation's scope slot lives in its shared context and
 * can be open concurrently on a Netty event-loop thread; racing on it
 * corrupted the scope, turned `/payments/authorize` into 500s after the
 * intent was persisted, and tripped the Orders→Payments breaker. The child
 * observation created here is owned exclusively by this thread; the parent's
 * context is only read.
 */
suspend fun <T> withRequestTraceLink(
    registry: ObservationRegistry,
    contextualName: String,
    block: () -> T,
): T {
    val parent = currentRequestObservation() ?: return block()
    val link = Observation.createNotStarted(TRACE_LINK_OBSERVATION_NAME, registry)
        .contextualName(contextualName)
        .parentObservation(parent)
        .start()
    try {
        return link.openScope().use { block() }
    } catch (e: Throwable) {
        link.error(e)
        throw e
    } finally {
        link.stop()
    }
}

const val TRACE_LINK_OBSERVATION_NAME = "kafka.publish.trace.link"
