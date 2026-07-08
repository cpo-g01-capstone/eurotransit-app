package com.eurotransit.notifications.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** One CoroutineScope per failure domain (CLAUDE.md). Cancelled on SIGTERM/app shutdown. */
@Configuration
class NotificationsLifecycle : DisposableBean {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Bean
    fun serviceScope(): CoroutineScope = scope

    override fun destroy() {
        scope.cancel()
    }
}
