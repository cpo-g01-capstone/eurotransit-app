package com.eurotransit.orders.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

/**
 * Dedicated WebClient for the Payments call — this IS the bulkhead (ADR 0018).
 *
 * The connection pool is private to this client and strictly bounded: a slow or
 * hanging Payments can exhaust AT MOST these 20 connections (+40 short-waiting
 * acquirers), never the shared Reactor resources used by the rest of Orders
 * (HTTP entry, other calls). Pool exhaustion surfaces in ≤500ms as an error the
 * retry/breaker machinery handles — failure is contained, not propagated.
 *
 * responseTimeout(2s) is the per-attempt deadline from ADR 0018: no unbounded
 * waits; a Payments slower than 2s counts as a slow call in the breaker window.
 */
@Configuration
class PaymentsWebClientConfig {

    @Bean("paymentsWebClient")
    fun paymentsWebClient(
        // In-cluster default = the Payments ClusterIP service; override via env for local dev.
        @Value("\${payments.base-url}") baseUrl: String,
    ): WebClient {
        val pool = ConnectionProvider.builder("payments-bulkhead")
            .maxConnections(20)
            .pendingAcquireMaxCount(40)
            .pendingAcquireTimeout(Duration.ofMillis(500))
            .build()

        val httpClient = HttpClient.create(pool)
            .responseTimeout(Duration.ofSeconds(2))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
