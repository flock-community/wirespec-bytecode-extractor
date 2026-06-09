package community.flock.wirespec.spring.extractor.it.gradle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the combined `messaging-app` fixture. Asserts that JMS, Rabbit,
 * Pulsar, and Spring Integration listeners — plus JMS/Rabbit (non-generic) and
 * Pulsar (generic) producers — all produce `channel` definitions per owner
 * class, and that shared payload DTOs float to types.ws.
 */
object MessagingFixtureVerifier {

    fun verify(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly(
            "IntegrationConsumer.ws",
            "JmsConsumer.ws",
            "JmsPublisher.ws",
            "PulsarConsumer.ws",
            "PulsarPublisher.ws",
            "RabbitConsumer.ws",
            "RabbitPublisher.ws",
            "types.ws",
        )

        val jmsConsumer = File(wsDir, "JmsConsumer.ws").readText()
        jmsConsumer shouldContain "channel OnJmsOrderCreated -> OrderEvent"
        jmsConsumer shouldContain "channel OnJmsOrderWithHeader -> OrderEvent"

        val jmsPublisher = File(wsDir, "JmsPublisher.ws").readText()
        jmsPublisher shouldContain "channel PublishJmsOrder -> OrderEvent"

        val rabbitConsumer = File(wsDir, "RabbitConsumer.ws").readText()
        rabbitConsumer shouldContain "channel OnRabbitOrderCreated -> OrderEvent"
        rabbitConsumer shouldContain "channel OnRabbitOrderMessage -> OrderEvent"

        val rabbitPublisher = File(wsDir, "RabbitPublisher.ws").readText()
        rabbitPublisher shouldContain "channel PublishRabbitShipment -> ShipmentEvent"

        val pulsarConsumer = File(wsDir, "PulsarConsumer.ws").readText()
        pulsarConsumer shouldContain "channel OnPulsarOrderCreated -> OrderEvent"
        pulsarConsumer shouldContain "channel OnPulsarOrderMessage -> OrderEvent"

        val pulsarPublisher = File(wsDir, "PulsarPublisher.ws").readText()
        pulsarPublisher shouldContain "channel PublishPulsarShipment -> ShipmentEvent"

        val integration = File(wsDir, "IntegrationConsumer.ws").readText()
        integration shouldContain "channel OnIntegrationOrder -> OrderEvent"

        // OrderEvent (many owners) and ShipmentEvent (Rabbit + Pulsar publishers)
        // are shared, so both float to types.ws.
        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type OrderEvent"
        types shouldContain "type ShipmentEvent"

        // Shared types must NOT leak into any per-class file.
        val perClass = listOf(
            jmsConsumer, jmsPublisher, rabbitConsumer, rabbitPublisher,
            pulsarConsumer, pulsarPublisher, integration,
        )
        listOf("OrderEvent", "ShipmentEvent").forEach { name ->
            perClass.forEach { file ->
                assertTrue(!Regex("(?m)^\\s*type\\s+$name\\b").containsMatchIn(file)) {
                    "$name leaked into a per-class .ws file despite being shared:\n$file"
                }
            }
        }
    }
}
