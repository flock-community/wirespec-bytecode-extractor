package community.flock.wirespec.spring.extractor.it

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the `kafka-app` Maven fixture. Mirrors the Gradle-side
 * `KafkaFixtureVerifier`, but reads from `target/wirespec` instead of
 * `build/wirespec`.
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

        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type OrderEvent"
        types shouldContain "type ShipmentEvent"

        listOf("OrderEvent", "ShipmentEvent").forEach { name ->
            listOf(consumer, shipment, publisher).forEach { file ->
                assertTrue(!Regex("(?m)^\\s*type\\s+$name\\b").containsMatchIn(file)) {
                    "$name leaked into a per-class .ws file despite being shared:\n$file"
                }
            }
        }
    }
}
