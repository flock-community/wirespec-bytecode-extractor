package community.flock.wirespec.spring.extractor.extract.messaging

/**
 * Per-broker descriptor that drives the generalized messaging extraction.
 * Every broker-specific fact (annotation FQNs, payload wrappers, producer
 * template) lives here so the Scanner/Selector/Walker/Extractor stay generic.
 *
 * All lookups are by string FQN: the broker libraries are intentionally NOT on
 * extractor-core's main classpath, so a broker silently no-ops when its types
 * are absent from the scanned ClassLoader.
 */
internal data class MessagingBroker(
    val id: String,
    val listenerAnnotation: String,
    /** Class-level multi-handler annotation (Kafka/Rabbit); null when unsupported. */
    val handlerAnnotation: String?,
    /** Generic wrappers to unwrap to a value-type argument (e.g. ConsumerRecord idx 1). */
    val recordWrappers: List<Wrapper>,
    /** Untyped parameter types treated as meta and ignored during payload selection. */
    val rawMetaTypes: List<String>,
    /** Producer template spec; null ⇒ listener-only (Spring Integration). */
    val producer: ProducerSpec?,
) {
    data class Wrapper(val fqn: String, val valueArgIndex: Int)

    companion object {
        val KAFKA = MessagingBroker(
            id = "kafka",
            listenerAnnotation = "org.springframework.kafka.annotation.KafkaListener",
            handlerAnnotation = "org.springframework.kafka.annotation.KafkaHandler",
            recordWrappers = listOf(Wrapper("org.apache.kafka.clients.consumer.ConsumerRecord", 1)),
            rawMetaTypes = listOf(
                "org.springframework.kafka.support.Acknowledgment",
                "org.apache.kafka.clients.consumer.Consumer",
            ),
            producer = ProducerSpec.GenericTemplate(
                fqn = "org.springframework.kafka.core.KafkaTemplate",
                valueArgIndex = 1,
                sendMethods = setOf("send"),
            ),
        )

        val JMS = MessagingBroker(
            id = "jms",
            listenerAnnotation = "org.springframework.jms.annotation.JmsListener",
            handlerAnnotation = null,
            recordWrappers = emptyList(),
            rawMetaTypes = listOf(
                "jakarta.jms.Message", "javax.jms.Message",
                "jakarta.jms.Session", "javax.jms.Session",
            ),
            producer = ProducerSpec.NonGenericTemplate(
                fqn = "org.springframework.jms.core.JmsTemplate",
                sendMethods = setOf("convertAndSend"),
            ),
        )

        val RABBIT = MessagingBroker(
            id = "rabbit",
            listenerAnnotation = "org.springframework.amqp.rabbit.annotation.RabbitListener",
            handlerAnnotation = "org.springframework.amqp.rabbit.annotation.RabbitHandler",
            recordWrappers = emptyList(),
            rawMetaTypes = listOf(
                "org.springframework.amqp.core.Message",
                "com.rabbitmq.client.Channel",
            ),
            producer = ProducerSpec.NonGenericTemplate(
                fqn = "org.springframework.amqp.rabbit.core.RabbitTemplate",
                sendMethods = setOf("convertAndSend"),
            ),
        )

        val PULSAR = MessagingBroker(
            id = "pulsar",
            listenerAnnotation = "org.springframework.pulsar.annotation.PulsarListener",
            handlerAnnotation = null,
            recordWrappers = listOf(
                Wrapper("org.apache.pulsar.client.api.Message", 0),
                Wrapper("org.apache.pulsar.client.api.Messages", 0),
            ),
            rawMetaTypes = listOf("org.apache.pulsar.client.api.Consumer"),
            producer = ProducerSpec.GenericTemplate(
                fqn = "org.springframework.pulsar.core.PulsarTemplate",
                valueArgIndex = 0,
                sendMethods = setOf("send", "sendAsync"),
            ),
        )

        val INTEGRATION = MessagingBroker(
            id = "integration",
            listenerAnnotation = "org.springframework.integration.annotation.ServiceActivator",
            handlerAnnotation = null,
            recordWrappers = emptyList(),
            rawMetaTypes = emptyList(),
            producer = null,
        )

        val ALL: List<MessagingBroker> = listOf(KAFKA, JMS, RABBIT, PULSAR, INTEGRATION)
    }
}

/** How a broker's producer template carries its payload type. */
internal sealed interface ProducerSpec {
    /** Payload type recoverable from the template field's generic signature (Kafka, Pulsar). */
    data class GenericTemplate(val fqn: String, val valueArgIndex: Int, val sendMethods: Set<String>) : ProducerSpec
    /** Non-generic template; payload type recovered from the send-call argument (JMS, Rabbit). */
    data class NonGenericTemplate(val fqn: String, val sendMethods: Set<String>) : ProducerSpec
}
