package com.eurotransit.orders.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import reactor.util.context.Context
import java.util.UUID

class OrderTraceTaggerTest {

    @Test
    fun `tags the request observation from the coroutine's Reactor context`() {
        val tracer = mock(Tracer::class.java)
        val observation = mock(Observation::class.java)
        val orderId = UUID.randomUUID()
        val reactorContext =
            Context.of(ObservationThreadLocalAccessor.KEY, observation).asCoroutineContext()

        runBlocking(reactorContext) {
            OrderTraceTagger(tracer).tag(orderId)
        }

        verify(observation)
            .highCardinalityKeyValue(OrderTraceTagger.ORDER_ID_ATTRIBUTE, orderId.toString())
        // The ThreadLocal span must not be consulted when the observation is
        // available — that path silently no-ops after suspension points.
        verifyNoInteractions(tracer)
    }

    @Test
    fun `falls back to the active ThreadLocal span outside a request coroutine`() {
        val tracer = mock(Tracer::class.java)
        val span = mock(Span::class.java)
        val orderId = UUID.randomUUID()
        `when`(tracer.currentSpan()).thenReturn(span)

        runBlocking {
            OrderTraceTagger(tracer).tag(orderId)
        }

        verify(span).tag(OrderTraceTagger.ORDER_ID_ATTRIBUTE, orderId.toString())
    }

    @Test
    fun `is a no-op when neither observation nor span is active`() {
        val tracer = mock(Tracer::class.java)
        `when`(tracer.currentSpan()).thenReturn(null)

        assertDoesNotThrow {
            runBlocking {
                OrderTraceTagger(tracer).tag(UUID.randomUUID())
            }
        }
    }
}
