package com.eurotransit.catalog.kafka

import com.eurotransit.catalog.event.InventoryReservedEvent
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer

/**
 * Regression for the "catalog cache never updates" incident: bytes captured
 * VERBATIM from the inventory-reserved topic (kafka-console-consumer) must
 * deserialize with the exact consumer configuration the service runs with.
 * The bug was invisible end-to-end: a failed value deserialization surfaced
 * as a null payload the listener skipped as poison.
 */
class InventoryReservedDeserializationTest {

    // As published by inventory (spring.json.add.type.headers=false — no headers):
    // note the epoch-decimal Instant and the plain-number BigDecimal.
    private val topicBytes = (
        """{"orderId":"cf10f8c5-87f9-4319-b05f-5705baf5e0e2",""" +
        """"routeId":"00000000-0000-0000-0000-000000000001","seats":2,""" +
        """"reservationId":"3b9332d3-a07f-4324-8f9a-007201b51617",""" +
        """"amount":39.80,"timestamp":1783785209.982349074}"""
    ).toByteArray()

    private fun deserializer(configs: Map<String, Any>): ErrorHandlingDeserializer<Any> =
        ErrorHandlingDeserializer<Any>().apply { configure(configs, false) }

    @Test
    fun `deserializes with the full production config (yml + listener-level default type)`() {
        val deser = deserializer(
            mapOf(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS to JsonDeserializer::class.java.name,
                JsonDeserializer.TRUSTED_PACKAGES to "com.eurotransit.*",
                JsonDeserializer.USE_TYPE_INFO_HEADERS to "false",
                JsonDeserializer.VALUE_DEFAULT_TYPE to InventoryReservedEvent::class.java.name,
            ),
        )
        val headers = RecordHeaders()
        val value = deser.deserialize("inventory-reserved", headers, topicBytes)

        assertNotNull(value, "topic bytes must deserialize — a null here is what froze the cache")
        val event = value as InventoryReservedEvent
        assertEquals(2, event.seats)
        assertEquals("00000000-0000-0000-0000-000000000001", event.routeId.toString())
    }

    @Test
    fun `without a default type the value comes back null - the failure mode we hit`() {
        val deser = deserializer(
            mapOf(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS to JsonDeserializer::class.java.name,
                JsonDeserializer.TRUSTED_PACKAGES to "com.eurotransit.*",
                JsonDeserializer.USE_TYPE_INFO_HEADERS to "false",
            ),
        )
        val headers = RecordHeaders()
        val value = deser.deserialize("inventory-reserved", headers, topicBytes)
        assertNull(value, "no target type -> ErrorHandlingDeserializer yields null (poison path)")
    }
}
