package com.eurotransit.catalog.hydration

import com.eurotransit.catalog.cache.RouteCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Hydrates the advisory cache from an Inventory snapshot at startup (issue #31).
 * Together with the listener's `auto.offset.reset=latest` this makes restarts
 * convergent: snapshot = authoritative state now, events = deltas from now on.
 * The old scheme (hardcoded seed + replay-from-earliest) re-derived the state
 * from history, so anything that changed seats outside the event stream — a
 * `just seed-db` reseed, a manual capacity fix — diverged the cache PERMANENTLY.
 *
 * Best-effort by design, mirroring Catalog's AP contract:
 *  - runs AFTER startup (ApplicationReadyEvent), off the main thread — Catalog
 *    comes up and serves the seed fallback even if Inventory is down (CE-1:
 *    browse must have no failure path in common with the money path, and
 *    inventory pods being dead is exactly the CE-2 scenario);
 *  - retries with capped backoff until it succeeds ONCE, then stops — from
 *    there the event stream keeps the cache warm;
 *  - a failed cycle leaves the previous state serving (seed fallback), which
 *    is the pre-#31 behaviour minus the replay double-decrement.
 *
 * Known race, accepted: an event consumed between the snapshot read and its
 * apply can be overwritten by the snapshot (which already includes its effect,
 * since Inventory commits the DB update before publishing) or lost for one
 * event's worth of staleness — advisory data, within contract.
 */
@Component
class RouteCacheHydrator(
    private val source: InventorySnapshotSource,
    private val cache: RouteCache,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch { hydrateUntilSuccess() }
    }

    internal suspend fun hydrateUntilSuccess(
        initialBackoff: Duration = Duration.ofSeconds(1),
        maxBackoff: Duration = Duration.ofSeconds(30),
    ) {
        var backoff = initialBackoff
        var attempt = 1
        while (true) {
            try {
                val snapshot = source.fetchRoutes()
                cache.hydrate(snapshot)
                logger.info(
                    "Advisory cache hydrated from Inventory snapshot: {} routes (attempt {})",
                    snapshot.size, attempt,
                )
                return
            } catch (e: Exception) {
                logger.warn(
                    "Inventory snapshot hydration failed (attempt {}), retrying in {}s — serving fallback seed until it succeeds: {}",
                    attempt, backoff.seconds, e.toString(),
                )
            }
            delay(backoff.toMillis())
            backoff = minOf(backoff.multipliedBy(2), maxBackoff)
            attempt++
        }
    }
}
