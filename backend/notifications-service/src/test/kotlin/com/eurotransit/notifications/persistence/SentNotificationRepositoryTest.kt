package com.eurotransit.notifications.persistence

import com.eurotransit.notifications.AbstractIntegrationTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class SentNotificationRepositoryTest(
    @Autowired val repository: SentNotificationRepository,
) : AbstractIntegrationTest() {

    @Test
    fun `claim inserts once then reports conflict`() = runTest {
        val id = "order-claim-1"
        assertEquals(1L, repository.claim(id))   // newly claimed
        assertEquals(0L, repository.claim(id))   // already exists
        assertEquals("PENDING", repository.findStatus(id))
    }

    @Test
    fun `updateStatus transitions the row`() = runTest {
        val id = "order-claim-2"
        repository.claim(id)
        assertEquals(1L, repository.updateStatus(id, "SENT"))
        assertEquals("SENT", repository.findStatus(id))
    }

    @Test
    fun `findStatus is null for unknown order`() = runTest {
        assertNull(repository.findStatus("does-not-exist"))
    }
}
