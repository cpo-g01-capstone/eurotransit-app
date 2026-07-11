package com.eurotransit.orders.lifecycle

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for [GracefulShutdownManager].
 *
 * These tests verify the shutdown lifecycle without requiring Spring context,
 * Kafka, or any infrastructure. The manager is tested in isolation by calling
 * SmartLifecycle methods directly.
 */
class GracefulShutdownManagerTest {

    private lateinit var manager: GracefulShutdownManager
    private lateinit var mockContext: ApplicationContext

    @BeforeEach
    fun setUp() {
        manager = GracefulShutdownManager()
        // Mock ApplicationContext — AvailabilityChangeEvent.publish() needs it but
        // in unit tests we only care about the state transitions, not the actual event.
        mockContext = mock(ApplicationContext::class.java)
        manager.setApplicationContext(mockContext)
        manager.start()
    }

    // ---- State transitions ----

    @Test
    @DisplayName("After start(), isAcceptingTraffic is true and isRunning is true")
    fun afterStart_acceptingTrafficAndRunning() {
        assertThat(manager.isAcceptingTraffic()).isTrue()
        assertThat(manager.isRunning).isTrue()
        assertThat(manager.inflightOperations()).isEqualTo(0)
    }

    @Test
    @DisplayName("After stop(), isAcceptingTraffic is false and isRunning is false")
    fun afterStop_notAcceptingAndNotRunning() {
        val callbackCalled = AtomicBoolean(false)
        manager.stop { callbackCalled.set(true) }

        assertThat(manager.isAcceptingTraffic()).isFalse()
        assertThat(manager.isRunning).isFalse()
        assertThat(callbackCalled.get()).isTrue()
    }

    @Test
    @DisplayName("stop() calls the callback even with zero in-flight operations")
    fun stopWithNoInflight_callbackCalledImmediately() {
        val callbackCalled = AtomicBoolean(false)
        manager.stop { callbackCalled.set(true) }

        assertThat(callbackCalled.get()).isTrue()
    }

    // ---- In-flight tracking ----

    @Test
    @DisplayName("trackInflight increments and decrements the counter correctly")
    fun trackInflight_countsCorrectly() = runBlocking {
        assertThat(manager.inflightOperations()).isEqualTo(0)

        manager.trackInflight {
            assertThat(manager.inflightOperations()).isEqualTo(1)
        }

        assertThat(manager.inflightOperations()).isEqualTo(0)
    }

    @Test
    @DisplayName("trackInflight decrements on exception (no leak)")
    fun trackInflight_decrementsOnException() = runBlocking {
        try {
            manager.trackInflight {
                assertThat(manager.inflightOperations()).isEqualTo(1)
                throw RuntimeException("simulated failure")
            }
        } catch (_: RuntimeException) {
            // expected
        }

        assertThat(manager.inflightOperations()).isEqualTo(0)
    }

    @Test
    @DisplayName("trackInflight returns the block's result")
    fun trackInflight_returnsResult() = runBlocking {
        val result = manager.trackInflight { 42 }
        assertThat(result).isEqualTo(42)
    }

    // ---- Drain behavior ----

    @Test
    @DisplayName("stop() waits for in-flight operations to complete before calling callback")
    fun stop_waitsForInflightToDrain() {
        val inflightStarted = CountDownLatch(1)
        val callbackCalled = AtomicBoolean(false)

        // Launch a coroutine that simulates a slow in-flight operation
        val job = Thread {
            runBlocking {
                manager.trackInflight {
                    inflightStarted.countDown()
                    delay(500) // simulate work
                }
            }
        }
        job.start()

        // Wait for the in-flight operation to actually start
        assertThat(inflightStarted.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(manager.inflightOperations()).isEqualTo(1)

        // Start shutdown in another thread (stop() blocks until drain)
        val shutdownThread = Thread {
            manager.stop { callbackCalled.set(true) }
        }
        shutdownThread.start()

        // Wait for everything to complete
        job.join(3000)
        shutdownThread.join(3000)

        assertThat(manager.inflightOperations()).isEqualTo(0)
        assertThat(callbackCalled.get()).isTrue()
        assertThat(manager.isAcceptingTraffic()).isFalse()
    }

    @Test
    @DisplayName("isAcceptingTraffic flips to false immediately on stop, before drain completes")
    fun stop_flipsAcceptingImmediately() {
        val inflightStarted = CountDownLatch(1)
        val acceptingDuringDrain = AtomicBoolean(true)

        val job = Thread {
            runBlocking {
                manager.trackInflight {
                    inflightStarted.countDown()
                    delay(1000)
                }
            }
        }
        job.start()
        inflightStarted.await(2, TimeUnit.SECONDS)

        val shutdownThread = Thread {
            manager.stop {
                // By the time callback runs, accepting should already be false
                acceptingDuringDrain.set(manager.isAcceptingTraffic())
            }
        }
        shutdownThread.start()

        // Give stop() a moment to flip the flag before drain finishes
        Thread.sleep(100)
        assertThat(manager.isAcceptingTraffic()).isFalse()

        job.join(3000)
        shutdownThread.join(3000)
        assertThat(acceptingDuringDrain.get()).isFalse()
    }

    // ---- Phase ----

    @Test
    @DisplayName("getPhase returns Int.MAX_VALUE (stops last)")
    fun phase_isMaxValue() {
        assertThat(manager.phase).isEqualTo(Int.MAX_VALUE)
    }
}
