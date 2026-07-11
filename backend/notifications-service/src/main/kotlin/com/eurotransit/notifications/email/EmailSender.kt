package com.eurotransit.notifications.email

import com.eurotransit.notifications.OrderConfirmedEvent

interface EmailSender {
    /** Sends the confirmation. May throw; the caller treats a throw as a send failure. */
    suspend fun send(event: OrderConfirmedEvent)
}
