package com.eurotransit.notifications.config

import com.eurotransit.notifications.OrderConfirmedEvent
import com.eurotransit.notifications.persistence.SentNotificationRepository
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.FixedBackOff

@Configuration
@EnableKafka
class KafkaConfig {

    @Bean
    fun consumerFactory(props: KafkaProperties): ConsumerFactory<String, OrderConfirmedEvent> {
        val config = props.buildConsumerProperties(null).toMutableMap()
        config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        val json = JsonDeserializer(OrderConfirmedEvent::class.java).apply {
            setUseTypeHeaders(false)
            addTrustedPackages("com.eurotransit.notifications")
        }
        return DefaultKafkaConsumerFactory(
            config,
            StringDeserializer(),
            ErrorHandlingDeserializer(json),
        )
    }

    /** Producer used only by the DLT recoverer. */
    @Bean
    fun dltKafkaTemplate(props: KafkaProperties): KafkaTemplate<String, Any> {
        val config = props.buildProducerProperties(null).toMutableMap()
        config[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        config[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JsonSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(config))
    }

    @Bean
    fun errorHandler(
        dltKafkaTemplate: KafkaTemplate<String, Any>,
        serviceScope: CoroutineScope,
        repository: SentNotificationRepository,
    ): DefaultErrorHandler {
        val publisher = DeadLetterPublishingRecoverer(dltKafkaTemplate) { record, _ ->
            TopicPartition("${record.topic()}.DLT", record.partition())
        }
        // Mark the row FAILED (fire-and-forget on the service scope — no runBlocking), then DLT.
        val log = org.slf4j.LoggerFactory.getLogger("NotificationsErrorHandler")
        val recoverer = ConsumerRecordRecoverer { record, ex ->
            // The record key is the orderId (the producer keys by order id); more robust than
            // casting the value, which the ErrorHandlingDeserializer may have replaced.
            val orderId = record.key() as? String ?: (record.value() as? OrderConfirmedEvent)?.orderId
            log.warn(
                "Recovering to DLT after retries: topic={} key={} valueType={} cause={}",
                record.topic(), record.key(), record.value()?.javaClass?.simpleName, ex.message,
            )
            if (orderId != null) {
                serviceScope.launch { repository.updateStatus(orderId, "FAILED") }
            }
            publisher.accept(record, ex)
        }
        // Team-owned knobs: 5 bounded retries (500ms apart) for send failures, then DLT.
        val bounded = FixedBackOff(500L, 5L)
        // Transient DB errors: retry forever (block-and-lag) rather than DLT/drop.
        val blockAndLag = FixedBackOff(5_000L, Long.MAX_VALUE)
        return DefaultErrorHandler(recoverer, bounded).apply {
            setBackOffFunction { _, ex -> if (isTransientDataAccess(ex)) blockAndLag else bounded }
        }
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, OrderConfirmedEvent>,
        errorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, OrderConfirmedEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, OrderConfirmedEvent>()
        factory.consumerFactory = consumerFactory
        factory.setCommonErrorHandler(errorHandler)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        factory.containerProperties.isStopImmediate = false  // graceful drain of the in-flight record
        // Trace-context propagation (config-repo ADR 0022): this factory is
        // custom-built, so Boot's spring.kafka.listener.observation-enabled property
        // does NOT apply here — it must be set in code or consumer spans won't join
        // the producer's trace.
        factory.containerProperties.isObservationEnabled = true
        return factory
    }

    private fun isTransientDataAccess(ex: Throwable?): Boolean {
        var e: Throwable? = ex
        while (e != null) {
            if (e is DataAccessResourceFailureException || e is R2dbcException) return true
            e = e.cause
        }
        return false
    }
}
