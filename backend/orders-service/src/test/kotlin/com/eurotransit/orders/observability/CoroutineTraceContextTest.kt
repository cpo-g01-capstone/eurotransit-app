package com.eurotransit.orders.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.ObservationView
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

        override fun onError(context: Observation.Context) {
            events += "error:${context.name}"
        }

        override fun onStop(context: Observation.Context) {
            events += "stop:${context.name}"
        }
    }

    private fun registryWith(handler: RecordingHandler): ObservationRegistry =
        ObservationRegistry.create().apply { observationConfig().observationHandler(handler) }

    @Test
    fun `runs the block in the scope of a child observation parented to the request observation`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)
        val requestObservation = Observation.start("http.server.requests", registry)
        val reactorContext =
            Context.of(ObservationThreadLocalAccessor.KEY, requestObservation).asCoroutineContext()

        var currentDuringBlock: Observation? = null
        val result = runBlocking(reactorContext) {
            withRequestTraceLink(registry, "publish order-placed") {
                currentDuringBlock = registry.currentObservation
                42
            }
        }

        assertEquals(42, result)
        // The block saw a CURRENT observation — infrastructure inside (KafkaTemplate)
        // parents to it — and it is the link, not the request observation itself:
        // its scope slot is private to this thread (the #42 revert lesson).
        assertNotNull(currentDuringBlock)
        assertSame(requestObservation, handler.linkParent)
        assertEquals(
            listOf(
                "start:http.server.requests",
                "start:$TRACE_LINK_OBSERVATION_NAME",
                "scopeOpened:$TRACE_LINK_OBSERVATION_NAME",
                "scopeClosed:$TRACE_LINK_OBSERVATION_NAME",
                "stop:$TRACE_LINK_OBSERVATION_NAME",
            ),
            handler.events,
        )
    }

    @Test
    fun `runs the block directly when no request observation exists`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)

        val result = runBlocking {
            withRequestTraceLink(registry, "publish order-placed") { "ok" }
        }

        assertEquals("ok", result)
        assertTrue(handler.events.isEmpty())
    }

    @Test
    fun `records the error, stops the link and rethrows when the block throws`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)
        val requestObservation = Observation.start("http.server.requests", registry)
        val reactorContext =
            Context.of(ObservationThreadLocalAccessor.KEY, requestObservation).asCoroutineContext()

        val thrown = assertThrows<IllegalStateException> {
            runBlocking(reactorContext) {
                withRequestTraceLink<Unit>(registry, "publish order-placed") {
                    throw IllegalStateException("kafka down")
                }
            }
        }

        assertEquals("kafka down", thrown.message)
        assertTrue(handler.events.contains("error:$TRACE_LINK_OBSERVATION_NAME"))
        assertTrue(handler.events.contains("stop:$TRACE_LINK_OBSERVATION_NAME"))
        assertTrue(handler.events.contains("scopeClosed:$TRACE_LINK_OBSERVATION_NAME"))
    }
}
