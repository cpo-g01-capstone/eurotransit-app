package com.eurotransit.inventory.web

import com.eurotransit.inventory.model.Route
import com.eurotransit.inventory.repository.RouteRepository
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Contract test for the snapshot feed Catalog hydrates from (issue #31):
 * the browse fields must be present under the names Catalog's
 * InventoryRouteSnapshot expects.
 */
@WebFluxTest(RouteSnapshotController::class)
class RouteSnapshotControllerTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockBean
    lateinit var routeRepository: RouteRepository

    @Test
    fun `serves the authoritative route state as a plain JSON array`() {
        val route = Route(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            origin = "Turin", destination = "Milan",
            departureTime = Instant.now().plus(7, ChronoUnit.DAYS),
            totalSeats = 100, availableSeats = 100, price = BigDecimal("19.90"),
        )
        given(routeRepository.findAll()).willReturn(flowOf(route))

        webTestClient.get().uri("/inventory/routes")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].id").isEqualTo("00000000-0000-0000-0000-000000000001")
            .jsonPath("$[0].availableSeats").isEqualTo(100)
            .jsonPath("$[0].totalSeats").isEqualTo(100)
            .jsonPath("$[0].origin").isEqualTo("Turin")
            .jsonPath("$[0].destination").isEqualTo("Milan")
            .jsonPath("$[0].price").isEqualTo(19.90)
    }
}
