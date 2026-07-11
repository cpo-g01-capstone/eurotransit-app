package com.eurotransit.orders.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.DisposableBean

@Configuration
class CoroutineConfig : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    
    // An application-wide scope for launching concurrent tasks tied to the app lifecycle
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Bean
    fun applicationCoroutineScope(): CoroutineScope = applicationScope

    override fun destroy() {
        log.info("SIGTERM received. Canceling application CoroutineScope to prevent orphaned tasks...")
        applicationScope.cancel()
    }
}
