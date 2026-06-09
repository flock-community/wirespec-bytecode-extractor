package community.flock.wirespec.spring.extractor.extract.messaging

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload

class MessagingPayloadSelectorTest {

    data class OrderEvent(val id: String)

    @Suppress("unused")
    class KafkaFixtures {
        fun plain(event: OrderEvent) {}
        fun withRecord(rec: ConsumerRecord<String, OrderEvent>) {}
        fun withMessage(msg: Message<OrderEvent>) {}
        fun batch(events: List<OrderEvent>) {}
        fun withPayloadAnnotation(@Payload event: OrderEvent, @Header("k") key: String) {}
        fun ambiguousTwoPayloads(a: OrderEvent, b: OrderEvent) {}
    }

    @Suppress("unused")
    class JmsFixtures {
        fun plain(event: OrderEvent) {}
        fun withRawJmsMessage(msg: jakarta.jms.Message) {}
        fun payloadPlusRawMessage(@Payload event: OrderEvent, raw: jakarta.jms.Message) {}
    }

    @Suppress("unused")
    class PulsarFixtures {
        fun withPulsarMessage(msg: org.apache.pulsar.client.api.Message<OrderEvent>) {}
    }

    private fun method(cls: Class<*>, name: String) = cls.declaredMethods.first { it.name == name }

    private fun selected(cls: Class<*>, name: String, broker: MessagingBroker): Class<*> {
        val r = MessagingPayloadSelector.select(method(cls, name), broker)
        r.shouldBeInstanceOf<MessagingPayloadSelector.Result.Selected>()
        return r.payloadType as Class<*>
    }

    @Test fun `kafka plain single param`() {
        selected(KafkaFixtures::class.java, "plain", MessagingBroker.KAFKA) shouldBe OrderEvent::class.java
    }

    @Test fun `kafka unwraps ConsumerRecord to V`() {
        selected(KafkaFixtures::class.java, "withRecord", MessagingBroker.KAFKA) shouldBe OrderEvent::class.java
    }

    @Test fun `kafka unwraps Message to T`() {
        selected(KafkaFixtures::class.java, "withMessage", MessagingBroker.KAFKA) shouldBe OrderEvent::class.java
    }

    @Test fun `kafka unwraps List for batch`() {
        selected(KafkaFixtures::class.java, "batch", MessagingBroker.KAFKA) shouldBe OrderEvent::class.java
    }

    @Test fun `kafka Payload annotation wins`() {
        selected(KafkaFixtures::class.java, "withPayloadAnnotation", MessagingBroker.KAFKA) shouldBe OrderEvent::class.java
    }

    @Test fun `kafka ambiguous is skipped`() {
        val r = MessagingPayloadSelector.select(method(KafkaFixtures::class.java, "ambiguousTwoPayloads"), MessagingBroker.KAFKA)
        r.shouldBeInstanceOf<MessagingPayloadSelector.Result.Skipped>()
        (r as MessagingPayloadSelector.Result.Skipped).reason shouldBe "ambiguous payload parameter"
    }

    @Test fun `jms plain single param`() {
        selected(JmsFixtures::class.java, "plain", MessagingBroker.JMS) shouldBe OrderEvent::class.java
    }

    @Test fun `jms raw javax-jakarta Message is skipped`() {
        val r = MessagingPayloadSelector.select(method(JmsFixtures::class.java, "withRawJmsMessage"), MessagingBroker.JMS)
        r.shouldBeInstanceOf<MessagingPayloadSelector.Result.Skipped>()
    }

    @Test fun `jms Payload wins over raw jms Message`() {
        selected(JmsFixtures::class.java, "payloadPlusRawMessage", MessagingBroker.JMS) shouldBe OrderEvent::class.java
    }

    @Test fun `pulsar unwraps pulsar Message to value`() {
        selected(PulsarFixtures::class.java, "withPulsarMessage", MessagingBroker.PULSAR) shouldBe OrderEvent::class.java
    }
}
