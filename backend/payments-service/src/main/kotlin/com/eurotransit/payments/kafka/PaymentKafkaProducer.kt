package com.eurotransit.payments.kafka

import com.eurotransit.payments.event.PaymentAuthorizedEvent
import com.eurotransit.payments.observability.withRequestTraceLink
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class PaymentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val observationRegistry: ObservationRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // withRequestTraceLink: the send runs past coroutine suspension points in
    // the authorize flow, where the ThreadLocal observation is gone — without
    // the link the producer span roots a NEW trace instead of joining the
    // Orders → Payments request trace.
    suspend fun sendPaymentAuthorized(event: PaymentAuthorizedEvent) {
        logger.info("Publishing payment-authorized for orderId={}", event.orderId)
        withRequestTraceLink(observationRegistry, "publish $TOPIC_PAYMENT_AUTHORIZED") {
            kafkaTemplate.send(TOPIC_PAYMENT_AUTHORIZED, event.orderId.toString(), event)
        }
    }

    companion object {
        const val TOPIC_PAYMENT_AUTHORIZED = "payment-authorized"
    }
}
