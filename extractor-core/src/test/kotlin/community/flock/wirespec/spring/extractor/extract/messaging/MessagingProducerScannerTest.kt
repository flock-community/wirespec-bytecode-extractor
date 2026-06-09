package community.flock.wirespec.spring.extractor.extract.messaging

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jms.core.JmsTemplate
import org.springframework.kafka.core.KafkaTemplate

class MessagingProducerScannerTest {

    data class Order(val id: String)

    private val pkg = "community.flock.wirespec.spring.extractor.extract.messaging"

    @Suppress("unused")
    class KafkaPublisher(private val orders: KafkaTemplate<String, Order>)

    @Suppress("unused")
    class JmsPublisher(private val jms: JmsTemplate)

    @Test fun `generic template recovers value class from field generics`() {
        val fields = MessagingProducerScanner.scan(
            classLoader = javaClass.classLoader,
            scanPackages = listOf(pkg),
            basePackage = null,
            broker = MessagingBroker.KAFKA,
        ).filter { it.ownerClass == KafkaPublisher::class.java }
        fields shouldHaveSize 1
        fields.single().fieldName shouldBe "orders"
        fields.single().valueClass shouldBe Order::class.java
    }

    @Test fun `non-generic template yields field with null value class`() {
        val fields = MessagingProducerScanner.scan(
            classLoader = javaClass.classLoader,
            scanPackages = listOf(pkg),
            basePackage = null,
            broker = MessagingBroker.JMS,
        ).filter { it.ownerClass == JmsPublisher::class.java }
        fields shouldHaveSize 1
        fields.single().fieldName shouldBe "jms"
        fields.single().valueClass shouldBe null
    }
}
