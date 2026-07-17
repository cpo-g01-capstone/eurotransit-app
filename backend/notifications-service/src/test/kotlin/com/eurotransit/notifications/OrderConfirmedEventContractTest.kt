package com.eurotransit.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.serializer.JsonDeserializer

/**
 * Parses REAL wire JSON through the same JsonDeserializer setup as KafkaConfig,
 * because the integration tests construct the event class directly in Kotlin
 * and therefore can never catch a deserialization contract break. This blind
 * spot has bitten twice: first when `customerContact` was required (every live
 * event → DLT), then when it was non-null-with-default — a Kotlin default only
 * covers an ABSENT property, and Orders serializes an explicit
 * `"customerContact": null` for contact-less orders, so every no-email
 * confirmation was silently dropped while email orders worked.
 */
class OrderConfirmedEventContractTest {

    private fun deserializer() = JsonDeserializer(OrderConfirmedEvent::class.java).apply {
        setUseTypeHeaders(false)
        addTrustedPackages("com.eurotransit.notifications")
    }

    @Test
    fun `accepts an explicit null customerContact (contact-less order)`() {
        val json = """{"orderId":"o-1","customerContact":null}"""

        val event = deserializer().deserialize("order-confirmed", json.toByteArray())

        assertEquals("o-1", event.orderId)
        assertNull(event.customerContact)
    }

    @Test
    fun `accepts a missing customerContact (pre-email producer versions)`() {
        val json = """{"orderId":"o-2"}"""

        val event = deserializer().deserialize("order-confirmed", json.toByteArray())

        assertEquals("o-2", event.orderId)
        assertNull(event.customerContact)
    }

    @Test
    fun `accepts a populated customerContact`() {
        val json = """{"orderId":"o-3","customerContact":"rider@example.com"}"""

        val event = deserializer().deserialize("order-confirmed", json.toByteArray())

        assertEquals("rider@example.com", event.customerContact)
    }
}
