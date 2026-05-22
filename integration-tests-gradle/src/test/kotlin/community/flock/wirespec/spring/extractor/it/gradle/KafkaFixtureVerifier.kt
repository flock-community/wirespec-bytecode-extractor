package community.flock.wirespec.spring.extractor.it.gradle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the `kafka-app` fixture. Asserts that @KafkaListener
 * consumer methods produce `channel` definitions in the per-class .ws file,
 * and that the payload DTO ends up inlined (single owner = the consumer).
 */
object KafkaFixtureVerifier {

    fun verify(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly("OrderConsumer.ws")

        val consumer = File(wsDir, "OrderConsumer.ws").readText()

        consumer shouldContain "channel OnOrderCreated -> OrderEvent"
        // Payload DTO is owned by the only file that references it.
        consumer shouldContain "type OrderEvent"
    }
}
