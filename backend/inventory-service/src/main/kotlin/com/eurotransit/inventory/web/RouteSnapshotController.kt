package com.eurotransit.inventory.web

import com.eurotransit.inventory.model.Route
import com.eurotransit.inventory.repository.RouteRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only snapshot of the authoritative route/seat state, consumed by
 * Catalog to hydrate its advisory cache at startup (issue #31 — replaces the
 * hardcoded seed mirror + replay-from-earliest, which diverged permanently
 * after out-of-band SQL changes like `just seed-db`).
 *
 * A plain point-in-time read: no locking against concurrent reservations.
 * Readers get a snapshot that may be a few events behind by the time they
 * apply it — Catalog's contract (advisory, AP/EL) absorbs that.
 */
@RestController
@RequestMapping("/inventory")
class RouteSnapshotController(private val routes: RouteRepository) {

    @GetMapping("/routes")
    fun listRoutes(): Flow<Route> = routes.findAll()
}
