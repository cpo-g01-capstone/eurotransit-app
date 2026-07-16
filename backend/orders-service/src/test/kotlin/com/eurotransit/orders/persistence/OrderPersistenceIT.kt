package com.eurotransit.orders.persistence

import com.eurotransit.orders.AbstractIntegrationTest
import com.eurotransit.orders.event.CreateOrderRequest
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.repository.IdempotencyRecordRepository
import com.eurotransit.orders.repository.OrderRepository
import com.eurotransit.orders.service.OrderService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Persistence round-trip for the checkout entry point against a real PostgreSQL.
 *
 * Regression guard: entities with client-assigned ids must be INSERTed, not
 * UPDATEd — `CoroutineCrudRepository.save()` on a pre-assigned @Id issues an
 * UPDATE for a row that does not exist yet and fails with
 * TransientDataAccessResourceException (never caught by CI before because no
 * test exercised the HTTP create path with a real database).
 */
class OrderPersistenceIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var idempotencyRecordRepository: IdempotencyRecordRepository

    @Test
    fun `placeOrder persists a brand-new DRAFT order and caches the response`() = runBlocking {
        val key = "it-${UUID.randomUUID()}"

        val (response, isNew) = orderService.placeOrder(key, CreateOrderRequest(UUID.randomUUID(), 1))

        assertTrue(isNew)
        val saved = orderRepository.findById(response.orderId)
        assertNotNull(saved, "the DRAFT order row must exist after placeOrder")
        assertEquals(OrderStatus.DRAFT, saved!!.status)
        assertNotNull(
            idempotencyRecordRepository.findByIdempotencyKey(key),
            "the idempotency record must be committed with the order",
        )
    }

    @Test
    fun `placeOrder persists an optional customer contact`() = runBlocking {
        val key = "it-contact-${UUID.randomUUID()}"

        val (response, _) = orderService.placeOrder(
            key,
            CreateOrderRequest(UUID.randomUUID(), 1, customerContact = "rider@example.com"),
        )

        val saved = orderRepository.findById(response.orderId)
        assertEquals("rider@example.com", saved!!.customerContact)
    }

    @Test
    fun `placeOrder leaves customer contact null when absent or blank`() = runBlocking {
        val absentKey = "it-nocontact-${UUID.randomUUID()}"
        val (absent, _) = orderService.placeOrder(absentKey, CreateOrderRequest(UUID.randomUUID(), 1))
        assertNull(orderRepository.findById(absent.orderId)!!.customerContact)

        val blankKey = "it-blank-${UUID.randomUUID()}"
        val (blank, _) = orderService.placeOrder(
            blankKey,
            CreateOrderRequest(UUID.randomUUID(), 1, customerContact = "   "),
        )
        assertNull(orderRepository.findById(blank.orderId)!!.customerContact)
    }

    @Test
    fun `placeOrder returns the cached response on a duplicate idempotency key`() = runBlocking {
        val key = "it-dup-${UUID.randomUUID()}"

        val (first, firstIsNew) = orderService.placeOrder(key, CreateOrderRequest(UUID.randomUUID(), 1))
        val (second, secondIsNew) = orderService.placeOrder(key, CreateOrderRequest(UUID.randomUUID(), 2))

        assertTrue(firstIsNew)
        assertFalse(secondIsNew)
        assertEquals(first.orderId, second.orderId)
    }
}
