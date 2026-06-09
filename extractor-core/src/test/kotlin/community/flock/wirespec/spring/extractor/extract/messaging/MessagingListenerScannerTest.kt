package community.flock.wirespec.spring.extractor.extract.messaging

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.annotation.RabbitHandler
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.jms.annotation.JmsListener

class MessagingListenerScannerTest {

    data class Order(val id: String)

    private val pkg = "community.flock.wirespec.spring.extractor.extract.messaging"

    @Suppress("unused")
    class JmsConsumer {
        @JmsListener(destination = "t1")
        fun onCreated(order: Order) {}

        @JmsListener(destination = "t2")
        fun onUpdated(order: Order) {}

        fun notAListener(order: Order) {}
    }

    @Test fun `discovers method-level JMS listeners`() {
        val sites = MessagingListenerScanner.scan(
            classLoader = javaClass.classLoader,
            scanPackages = listOf(pkg),
            basePackage = null,
            broker = MessagingBroker.JMS,
        )
        val ours = sites.filter { it.ownerClass == JmsConsumer::class.java }
        ours shouldHaveSize 2
        ours.map { it.method.name }.toSet() shouldBe setOf("onCreated", "onUpdated")
    }

    @RabbitListener(queues = ["orders"])
    @Suppress("unused")
    class RabbitRouter {
        @RabbitHandler
        fun onCreated(event: Order) {}

        @RabbitHandler
        fun onUpdated(event: Order) {}
    }

    @Test fun `discovers RabbitHandler methods under class-level RabbitListener`() {
        val sites = MessagingListenerScanner.scan(
            classLoader = javaClass.classLoader,
            scanPackages = listOf(pkg),
            basePackage = null,
            broker = MessagingBroker.RABBIT,
        )
        val ours = sites.filter { it.ownerClass == RabbitRouter::class.java }
        ours shouldHaveSize 2
        ours.map { it.method.name }.toSet() shouldBe setOf("onCreated", "onUpdated")
    }
}
