package community.flock.wirespec.bytecode.extractor.it.gradle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the `kafka-app` fixture. Asserts that @KafkaListener,
 * @KafkaHandler, and KafkaTemplate.send call sites all produce `channel`
 * definitions in the per-class .ws file, and that shared payload DTOs float
 * to types.ws via TypeOwnership.
 */
object KafkaFixtureVerifier {

    fun verify(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly(
            "OrderConsumer.ws",
            "OrderPublisher.ws",
            "ShipmentRouter.ws",
            "types.ws",
        )

        val consumer = File(wsDir, "OrderConsumer.ws").readText()
        consumer shouldContain "channel OnOrderCreated -> OrderEvent"
        consumer shouldContain "channel OnOrderRecord -> OrderEvent"
        consumer shouldContain "channel OnOrderMessage -> OrderEvent"
        consumer shouldContain "channel OnOrderBatch -> OrderEvent"
        consumer shouldContain "channel OnOrderWithHeader -> OrderEvent"

        val shipment = File(wsDir, "ShipmentRouter.ws").readText()
        shipment shouldContain "channel OnShipmentCreated -> ShipmentEvent"
        shipment shouldContain "channel OnOrderShipped -> OrderEvent"

        val publisher = File(wsDir, "OrderPublisher.ws").readText()
        publisher shouldContain "channel PublishOrder -> OrderEvent"
        publisher shouldContain "channel PublishShipment -> ShipmentEvent"

        // Both OrderEvent (Consumer + Router + Publisher) and ShipmentEvent
        // (Router + Publisher) have multiple owners — both float to types.ws.
        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type OrderEvent"
        types shouldContain "type ShipmentEvent"

        // Shared types must NOT leak back into any per-class file.
        listOf("OrderEvent", "ShipmentEvent").forEach { name ->
            listOf(consumer, shipment, publisher).forEach { file ->
                assertTrue(!Regex("(?m)^\\s*type\\s+$name\\b").containsMatchIn(file)) {
                    "$name leaked into a per-class .ws file despite being shared:\n$file"
                }
            }
        }
    }
}
