package com.eurotransit.orders.config

import com.eurotransit.orders.AbstractIntegrationTest
import com.eurotransit.orders.event.OrderFailedEvent
import com.eurotransit.orders.kafka.OrderKafkaProducer
import com.eurotransit.orders.model.Order
import com.eurotransit.orders.model.OrderStatus
import com.eurotransit.orders.repository.OrderRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

/**
 * Semantics of the exhaustion recoverer against a REAL database (the case-17
 * lesson: money-path behaviour is tested on the real write path, not mocks).
 *
 * The contract under test (agent-log case 24, found live in CE-1 run 3):
 * an exhausted redelivery must publish the `order-failed` compensation ONLY
 * when the order actually failed — never for an order that reached a terminal
 * SUCCESS state through a competing path, because Inventory would release the
 * seats of a confirmed order.
 */
class OrderFailureRecovererIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var entityTemplate: R2dbcEntityTemplate

    private val published = mutableListOf<OrderFailedEvent>()
    private val registry = SimpleMeterRegistry()

    private fun recoverer() = OrderFailureRecoverer(
        orderRepository = orderRepository,
        publishOrderFailed = { published += it },
        meterRegistry = registry,
    )

    private fun newOrder(status: OrderStatus): UUID = runBlocking {
        val id = UUID.randomUUID()
        entityTemplate.insert(Order(id = id, status = status)).awaitSingle()
        id
    }

    private fun record(topic: String, orderId: UUID): ConsumerRecord<Any, Any> =
        ConsumerRecord(topic, 0, 0L, orderId.toString() as Any, "payload" as Any)

    private fun declinedCount(): Double =
        registry.counter("orders_compensation_declined_total").count()

    @Test
    fun `RESERVED order is failed and compensated - the designed path`() = runBlocking {
        val id = newOrder(OrderStatus.RESERVED)

        recoverer().accept(record("inventory-reserved", id), RuntimeException("payments down"))

        assertEquals(OrderStatus.FAILED, orderRepository.findStatusById(id))
        assertEquals(listOf(id), published.map { it.orderId })
        assertEquals(0.0, declinedCount())
    }

    @Test
    fun `CONFIRMED order is NOT compensated - the case-24 guard`() = runBlocking {
        val id = newOrder(OrderStatus.CONFIRMED)

        recoverer().accept(record("inventory-reserved", id), RuntimeException("late exhaustion"))

        assertEquals(OrderStatus.CONFIRMED, orderRepository.findStatusById(id), "status untouched")
        assertTrue(published.isEmpty(), "must NOT publish order-failed for a confirmed order")
        assertEquals(1.0, declinedCount(), "the guard branch is observable")
    }

    @Test
    fun `already-FAILED order still publishes - at-least-once survives a recoverer crash replay`() = runBlocking {
        val id = newOrder(OrderStatus.FAILED)

        recoverer().accept(record("payment-authorized", id), RuntimeException("replay"))

        assertEquals(listOf(id), published.map { it.orderId })
        assertEquals(0.0, declinedCount())
    }

    @Test
    fun `order-failed topic records never republish - the feedback-loop guard`() = runBlocking {
        val id = newOrder(OrderStatus.RESERVED)

        recoverer().accept(record(OrderKafkaProducer.TOPIC_ORDER_FAILED, id), RuntimeException("db down"))

        assertEquals(OrderStatus.FAILED, orderRepository.findStatusById(id), "transition still applies")
        assertTrue(published.isEmpty(), "no republish for order-failed records")
    }
}
