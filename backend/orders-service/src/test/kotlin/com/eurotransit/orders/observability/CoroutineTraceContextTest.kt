package com.eurotransit.orders.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.util.context.Context

class CoroutineTraceContextTest {

    @Test
    fun `restores the observation scope around the block and closes it`() {
        val observation = mock(Observation::class.java)
        val scope = mock(Observation.Scope::class.java)
        `when`(observation.openScope()).thenReturn(scope)
        val reactorContext =
            Context.of(ObservationThreadLocalAccessor.KEY, observation).asCoroutineContext()

        val result = runBlocking(reactorContext) {
            withRequestObservation { 42 }
        }

        assertEquals(42, result)
        verify(observation).openScope()
        verify(scope).close()
    }

    @Test
    fun `runs the block directly when no request observation exists`() {
        val result = runBlocking {
            withRequestObservation { "ok" }
        }

        assertEquals("ok", result)
    }
}
