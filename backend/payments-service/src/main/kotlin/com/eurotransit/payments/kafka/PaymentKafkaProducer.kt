package com.eurotransit.payments.kafka

import com.eurotransit.payments.event.PaymentAuthorizedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class PaymentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun sendPaymentAuthorized(event: PaymentAuthorizedEvent) {
        logger.info("Publishing payment-authorized for orderId={}", event.orderId)
        kafkaTemplate.send(TOPIC_PAYMENT_AUTHORIZED, event.orderId.toString(), event)
    }

    companion object {
        const val TOPIC_PAYMENT_AUTHORIZED = "payment-authorized"
    }
}
