package com.eurotransit.payments.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.ObservationView
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.util.context.Context

class CoroutineTraceContextTest {

    private class RecordingHandler : ObservationHandler<Observation.Context> {
        val events = mutableListOf<String>()
        var linkParent: ObservationView? = null

        override fun supportsContext(context: Observation.Context) = true

        override fun onStart(context: Observation.Context) {
            events += "start:${context.name}"
            if (context.name == TRACE_LINK_OBSERVATION_NAME) {
                linkParent = context.parentObservation
            }
        }

        override fun onScopeOpened(context: Observation.Context) {
            events += "scopeOpened:${context.name}"
        }

        override fun onScopeClosed(context: Observation.Context) {
            events += "scopeClosed:${context.name}"
        }

        override fun onStop(context: Observation.Context) {
            events += "stop:${context.name}"
        }
    }

    @Test
    fun `links the send to the request observation via an exclusively-owned child scope`() {
        val handler = RecordingHandler()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(handler)
        }
        val requestObservation = Observation.start("http.server.requests", registry)
        val reactorContext =
            Context.of(ObservationThreadLocalAccessor.KEY, requestObservation).asCoroutineContext()

        val result = runBlocking(reactorContext) {
            withRequestTraceLink(registry, "publish payment-authorized") { 42 }
        }

        assertEquals(42, result)
        assertSame(requestObservation, handler.linkParent)
        // The request observation's own scope is never opened — only the child's
        // (the exact hazard behind the #42 revert).
        assertTrue(handler.events.contains("scopeOpened:$TRACE_LINK_OBSERVATION_NAME"))
        assertTrue(handler.events.contains("scopeClosed:$TRACE_LINK_OBSERVATION_NAME"))
        assertTrue(handler.events.contains("stop:$TRACE_LINK_OBSERVATION_NAME"))
        assertTrue(handler.events.none { it == "scopeOpened:http.server.requests" })
    }

    @Test
    fun `runs the block directly when no request observation exists`() {
        val registry = ObservationRegistry.create()

        val result = runBlocking {
            withRequestTraceLink(registry, "publish payment-authorized") { "ok" }
        }

        assertEquals("ok", result)
    }
}
