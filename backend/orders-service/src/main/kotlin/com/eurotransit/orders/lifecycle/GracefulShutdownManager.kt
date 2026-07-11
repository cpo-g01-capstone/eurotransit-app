package com.eurotransit.orders.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages graceful shutdown for the Orders service.
 *
 * Shutdown sequence (triggered by SIGTERM):
 * 1. Mark readiness as REFUSING_TRAFFIC → K8s removes pod from endpoints
 * 2. Wait for in-flight Kafka operations to complete (max ~45s)
 * 3. Signal Spring to continue with remaining shutdown phases
 *
 * Usage in Kafka consumers:
 *   if (!shutdownManager.isAcceptingTraffic()) return  // skip, will rebalance
 *   shutdownManager.trackInflight { /* business logic */ }
 *
 * Phase = Int.MAX_VALUE → Spring stops SmartLifecycle beans in DESCENDING
 * phase order, so this bean stops FIRST — before the Kafka listener
 * containers (default phase Int.MAX_VALUE - 100). That is exactly what the
 * design needs: readiness flips and the drain starts while the containers
 * are still polling; the isAcceptingTraffic() guard makes consumers skip
 * any new message without ack, so it is redelivered after rebalance.
 */
@Component
class GracefulShutdownManager : SmartLifecycle, ApplicationContextAware {

    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val accepting = AtomicBoolean(true)
    private val inflightCount = AtomicInteger(0)
    private lateinit var applicationContext: ApplicationContext

    override fun setApplicationContext(ctx: ApplicationContext) {
        this.applicationContext = ctx
    }

    /** True while the service accepts new work (false after SIGTERM). */
    fun isAcceptingTraffic(): Boolean = accepting.get()

    /** Current number of in-flight operations (observable via metrics/logs). */
    fun inflightOperations(): Int = inflightCount.get()

    /**
     * Wraps a suspend block as an in-flight operation.
     * The shutdown sequence waits for all tracked operations to complete.
     */
    suspend fun <T> trackInflight(block: suspend () -> T): T {
        inflightCount.incrementAndGet()
        try {
            return block()
        } finally {
            inflightCount.decrementAndGet()
        }
    }

    override fun start() {
        running.set(true)
        accepting.set(true)
        log.info("GracefulShutdownManager started")
    }

    override fun stop(callback: Runnable) {
        log.info("Graceful shutdown initiated — flipping readiness to REFUSING_TRAFFIC")
        accepting.set(false)

        // 1. Flip readiness → Actuator /readiness returns 503 → K8s removes from endpoints
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)

        // 2. Drain in-flight operations (budget: ~45s, leaving margin within 50s Spring timeout)
        val deadlineMs = System.currentTimeMillis() + 45_000
        while (inflightCount.get() > 0 && System.currentTimeMillis() < deadlineMs) {
            log.info("Draining: {} in-flight operations remaining", inflightCount.get())
            Thread.sleep(500)
        }
        if (inflightCount.get() > 0) {
            log.warn("Drain timeout — {} operations still in-flight, proceeding with shutdown",
                inflightCount.get())
        } else {
            log.info("All in-flight operations drained successfully")
        }

        running.set(false)
        callback.run()
    }

    override fun stop() { stop {} }
    override fun isRunning(): Boolean = running.get()
    override fun getPhase(): Int = Int.MAX_VALUE // highest phase: started last, stopped FIRST on shutdown
}
