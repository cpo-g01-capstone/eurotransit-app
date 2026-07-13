package com.eurotransit.catalog.hydration

import com.eurotransit.catalog.cache.CatalogRoute
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.netty.channel.ChannelOption
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Where the hydrator gets its snapshot from — seam for tests. */
fun interface InventorySnapshotSource {
    suspend fun fetchRoutes(): List<CatalogRoute>
}

/**
 * Inventory's route row as served by `GET /inventory/routes`. Field shape
 * mirrors the producer's `Route` entity; `version` (and anything else added
 * later) is deliberately ignored — Catalog only needs the browse fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InventoryRouteSnapshot(
    val id: UUID,
    val origin: String,
    val destination: String,
    val departureTime: Instant,
    val totalSeats: Int,
    val availableSeats: Int,
    val price: BigDecimal,
) {
    fun toCatalogRoute() = CatalogRoute(
        id = id, origin = origin, destination = destination,
        departureTime = departureTime, totalSeats = totalSeats,
        availableSeats = availableSeats, price = price,
    )
}

/**
 * One-shot snapshot read of the authoritative seat state (issue #31).
 *
 * This is a STARTUP-ONLY dependency, not a read-path one: the browse endpoints
 * still serve purely from the in-memory cache, so ADR 0006's CE-1 containment
 * claim (no synchronous Inventory call per request) is untouched. Tight
 * timeouts because the hydrator retries anyway — better to fail fast and try
 * again than to hang startup hydration on a wedged connection.
 */
@Component
class InventorySnapshotClient(
    webClientBuilder: WebClient.Builder,
    // In-cluster default = the Inventory ClusterIP service; override via
    // INVENTORY_BASE_URL for local dev (e.g. http://localhost:8083).
    @Value("\${inventory.base-url}") baseUrl: String,
) : InventorySnapshotSource {

    private val client = webClientBuilder
        .baseUrl(baseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(5))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
            )
        )
        .build()

    override suspend fun fetchRoutes(): List<CatalogRoute> =
        client.get().uri("/inventory/routes")
            .retrieve()
            .bodyToFlux(InventoryRouteSnapshot::class.java)
            .asFlow()
            .toList()
            .map { it.toCatalogRoute() }
}
