package community.flock.wirespec.spring.extractor.extract.messaging

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jms.core.JmsTemplate
import org.springframework.kafka.core.KafkaTemplate

class MessagingProducerWalkerTest {

    data class Order(val id: String)
    data class Shipment(val id: String)

    // --- Generic template (Kafka): value from field generics ---
    @Suppress("unused")
    class KafkaPublisher(
        private val orders: KafkaTemplate<String, Order>,
        private val shipments: KafkaTemplate<String, Shipment>,
    ) {
        fun publishOrder(order: Order) { orders.send("orders.created", order) }
        fun publishShipment(shipment: Shipment) { shipments.send("shipments.created", shipment) }
        fun noSend(order: Order) {}
    }

    @Test fun `generic template uses field value class`() {
        val fields = listOf(
            MessagingProducerScanner.TemplateField(KafkaPublisher::class.java, "orders", Order::class.java),
            MessagingProducerScanner.TemplateField(KafkaPublisher::class.java, "shipments", Shipment::class.java),
        )
        val sites = MessagingProducerWalker.walk(KafkaPublisher::class.java, fields, MessagingBroker.KAFKA)
        sites.map { it.enclosingMethod to it.valueClass.simpleName }.toSet() shouldBe setOf(
            "publishOrder" to "Order",
            "publishShipment" to "Shipment",
        )
    }

    // --- Non-generic template (JMS): value from send-call argument ---
    @Suppress("unused")
    class JmsPublisher(private val jms: JmsTemplate) {
        private val cached = Order("c")
        fun sendParam(order: Order) { jms.convertAndSend("orders", order) }
        fun sendField() { jms.convertAndSend("orders", cached) }
        fun sendNew() { jms.convertAndSend("orders", Order("n")) }
        fun sendReturn() { jms.convertAndSend("orders", make()) }
        fun sendString() { jms.convertAndSend("orders", "not-a-dto") }
        private fun make(): Shipment = Shipment("s")
    }

    @Test fun `non-generic template recovers payload type from arg`() {
        val fields = listOf(
            MessagingProducerScanner.TemplateField(JmsPublisher::class.java, "jms", null),
        )
        val sites = MessagingProducerWalker.walk(JmsPublisher::class.java, fields, MessagingBroker.JMS)
        val byMethod = sites.associate { it.enclosingMethod to it.valueClass.simpleName }
        byMethod["sendParam"] shouldBe "Order"
        byMethod["sendField"] shouldBe "Order"
        byMethod["sendNew"] shouldBe "Order"
        byMethod["sendReturn"] shouldBe "Shipment"
        // String payload is JDK noise → not extracted.
        byMethod.containsKey("sendString") shouldBe false
    }

    @Test fun `non-generic recovers exactly the four resolvable sites`() {
        val fields = listOf(MessagingProducerScanner.TemplateField(JmsPublisher::class.java, "jms", null))
        val sites = MessagingProducerWalker.walk(JmsPublisher::class.java, fields, MessagingBroker.JMS)
        sites shouldHaveSize 4
    }
}
