package com.eurotransit.orders.observability

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class OrderTraceTaggerTest {

    @Test
    fun `tags the active span with the searchable order id`() {
        val tracer = mock(Tracer::class.java)
        val span = mock(Span::class.java)
        val orderId = UUID.randomUUID()
        `when`(tracer.currentSpan()).thenReturn(span)

        OrderTraceTagger(tracer).tag(orderId)

        verify(span).tag(OrderTraceTagger.ORDER_ID_ATTRIBUTE, orderId.toString())
    }

    @Test
    fun `is a no-op when no trace span is active`() {
        val tracer = mock(Tracer::class.java)
        `when`(tracer.currentSpan()).thenReturn(null)

        assertDoesNotThrow {
            OrderTraceTagger(tracer).tag(UUID.randomUUID())
        }
    }
}
