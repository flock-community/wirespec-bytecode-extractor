package community.flock.wirespec.extractor.it

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the combined `messaging-app` Maven fixture. Mirrors the
 * Gradle-side `MessagingFixtureVerifier`, reading from `target/wirespec`.
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

        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type OrderEvent"
        types shouldContain "type ShipmentEvent"

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
