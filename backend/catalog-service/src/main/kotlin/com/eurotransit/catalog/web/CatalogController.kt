package com.eurotransit.catalog.web

import com.eurotransit.catalog.cache.RouteCache
import com.eurotransit.catalog.cache.CatalogRoute
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Read-only browsing API (the "mostly reads, tolerant of staleness" service).
 * Availability shown here is ADVISORY — the authoritative seat count lives in
 * Inventory behind the CP reservation path. Serving from the in-memory cache
 * keeps this endpoint fast and dependency-free: during chaos experiment CE-1
 * this is the surface that must STAY HEALTHY while Payments is degraded
 * (failure containment — the whole point of the check).
 */
@RestController
@RequestMapping("/catalog")
class CatalogController(private val cache: RouteCache) {

    @GetMapping
    fun listRoutes(): List<CatalogRoute> = cache.all()

    @GetMapping("/{id}")
    fun getRoute(@PathVariable id: UUID): ResponseEntity<CatalogRoute> =
        cache.byId(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
