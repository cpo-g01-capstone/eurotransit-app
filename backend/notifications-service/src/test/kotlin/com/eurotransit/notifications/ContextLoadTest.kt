package com.eurotransit.notifications

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient

class ContextLoadTest(@Autowired val db: DatabaseClient) : AbstractIntegrationTest() {

    @Test
    fun `context loads and flyway created sent_notifications`() = runTest {
        // Verifies the context starts and Flyway created the table (queryable). The DB is a shared
        // singleton across test classes, so the row count is not asserted to be zero.
        val count = db.sql("SELECT count(*) AS c FROM sent_notifications")
            .map { row -> row.get("c", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()
        assertTrue(count >= 0L)
    }
}
