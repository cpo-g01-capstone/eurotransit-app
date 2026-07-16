package com.eurotransit.orders.kafka

import com.eurotransit.orders.event.OrderConfirmedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The `order-confirmed` contract carries an OPTIONAL customer contact. Keeping
 * the field nullable/defaulted is the regression guard against the earlier DLT
 * bug where a required contact rejected every real event.
 */
class OrderConfirmedContactTest {

    @Test
    fun `order-confirmed carries an optional customer contact`() {
        val id = UUID.randomUUID()

        val withContact = OrderConfirmedEvent(orderId = id, customerContact = "rider@example.com")
        assertEquals("rider@example.com", withContact.customerContact)
    }

    @Test
    fun `customer contact defaults to null for backward compatibility`() {
        val withoutContact = OrderConfirmedEvent(orderId = UUID.randomUUID())
        assertNull(withoutContact.customerContact)
    }
}
