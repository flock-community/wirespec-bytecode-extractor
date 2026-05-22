package community.flock.wirespec.spring.extractor.extract.kafka

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.kafka.annotation.KafkaListener

class KafkaListenerScannerTest {

    data class Order(val id: String)

    @Suppress("unused")
    class OrderConsumer {
        @KafkaListener(topics = ["t1"])
        fun onCreated(order: Order) {}

        @KafkaListener(topics = ["t2"])
        fun onUpdated(order: Order) {}

        fun notAListener(order: Order) {}
    }

    @Test
    fun `discovers all method-level @KafkaListener methods`() {
        val loader = javaClass.classLoader
        val sites = KafkaListenerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("community.flock.wirespec.spring.extractor.extract.kafka"),
            basePackage = null,
        )
        val ours = sites.filter { it.ownerClass == OrderConsumer::class.java }
        ours shouldHaveSize 2
        ours.map { it.method.name }.toSet() shouldBe setOf("onCreated", "onUpdated")
    }
}
