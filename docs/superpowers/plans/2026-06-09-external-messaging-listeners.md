# External Messaging Listeners Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Wirespec `channel` definitions from Spring JMS, RabbitMQ, Pulsar, and Spring Integration listeners (and JMS/Rabbit/Pulsar producers), by generalizing the existing Kafka extractor into a broker-descriptor-driven `extract.messaging` package.

**Architecture:** Rename `extractor-core/.../extract/kafka/` to `extract/messaging/` and replace the Kafka-specific Scanner/PayloadSelector/ProducerScanner/Walker/ChannelExtractor with broker-parameterized versions driven by a `MessagingBroker` descriptor table. `WirespecExtractor` loops over five descriptors (Kafka + four new). Listener payload selection reuses the shared Spring `@Payload`/`@Header`/`Message<T>` logic; producers use field-generic recovery for generic templates (Kafka, Pulsar) and best-effort bytecode argument-type recovery for non-generic templates (JMS, Rabbit). Spring Integration is listener-only.

**Tech Stack:** Kotlin (JVM 21, `apiVersion`/`languageVersion` pinned to 2.0), ClassGraph (annotation/field scanning), ASM tree API (bytecode walking), JUnit 5 + Kotest assertions, Gradle TestKit + Maven invoker integration tests.

**Spec:** `docs/superpowers/specs/2026-06-09-external-messaging-listeners-design.md`

---

## Reference: existing Kafka code (being generalized)

These existing files are the source-of-truth for the patterns. They will be **deleted** in Task 9 after the generalized versions are wired in:

- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt`
- `.../extract/kafka/KafkaPayloadSelector.kt`
- `.../extract/kafka/KafkaProducerScanner.kt`
- `.../extract/kafka/KafkaProducerBytecodeWalker.kt`
- `.../extract/kafka/KafkaChannelExtractor.kt`
- Tests under `extractor-core/src/test/.../extract/kafka/`

The orchestration to replace lives in `WirespecExtractor.kt:110-145`.

## Version pins (verify at implementation time)

These versions are compatible with Spring Framework 6.1.14. If resolution fails, check the latest patch in the same minor line and update both the catalog (Task 1) and fixture build files (Tasks 10–11):

| Library | Coordinates | Version |
|---|---|---|
| Spring JMS | `org.springframework:spring-jms` | `6.1.14` |
| Spring AMQP (Rabbit) | `org.springframework.amqp:spring-rabbit` | `3.1.7` |
| Spring for Apache Pulsar | `org.springframework.pulsar:spring-pulsar` | `1.0.8` |
| Spring Integration | `org.springframework.integration:spring-integration-core` | `6.2.7` |
| Spring Messaging | `org.springframework:spring-messaging` | `6.1.14` |

---

## Task 1: Add broker libraries to the version catalog and extractor-core test classpath

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `extractor-core/build.gradle.kts`

These libraries stay **off** the main classpath (string-FQN lookups keep the extractor a no-op when a broker is absent). They are added to extractor-core's **test** classpath so unit tests can use the real annotations/types.

- [ ] **Step 1: Add versions to the catalog**

In `gradle/libs.versions.toml`, under `[versions]`, after the `spring-kafka = "3.2.4"` line add:

```toml
spring-amqp = "3.1.7"
spring-pulsar = "1.0.8"
spring-integration = "6.2.7"
```

- [ ] **Step 2: Add libraries to the catalog**

In `gradle/libs.versions.toml`, under `[libraries]`, after the `spring-kafka = { ... }` line add:

```toml
spring-jms = { module = "org.springframework:spring-jms", version.ref = "spring" }
spring-messaging = { module = "org.springframework:spring-messaging", version.ref = "spring" }
spring-rabbit = { module = "org.springframework.amqp:spring-rabbit", version.ref = "spring-amqp" }
spring-pulsar = { module = "org.springframework.pulsar:spring-pulsar", version.ref = "spring-pulsar" }
spring-integration-core = { module = "org.springframework.integration:spring-integration-core", version.ref = "spring-integration" }
```

- [ ] **Step 3: Add test dependencies to extractor-core**

In `extractor-core/build.gradle.kts`, in the `dependencies { }` block, immediately after the line `testImplementation(libs.spring.kafka)` add:

```kotlin
    testImplementation(libs.spring.jms)
    testImplementation(libs.spring.messaging)
    testImplementation(libs.spring.rabbit)
    testImplementation(libs.spring.pulsar)
    testImplementation(libs.spring.integration.core)
```

- [ ] **Step 4: Verify the dependencies resolve and compile**

Run: `./gradlew :extractor-core:compileTestKotlin`
Expected: BUILD SUCCESSFUL (dependencies download; existing code still compiles).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml extractor-core/build.gradle.kts
git commit -m "build: add JMS/Rabbit/Pulsar/Integration test deps for messaging extraction"
```

---

## Task 2: Create the MessagingBroker descriptor and broker table

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingBroker.kt`

This descriptor is the single source of truth for every per-broker fact. It has no dependencies on the other new classes, so it compiles standalone. The old `extract.kafka` package remains in place and untouched until Task 9.

- [ ] **Step 1: Write the descriptor and table**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingBroker.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.messaging

/**
 * Per-broker descriptor that drives the generalized messaging extraction.
 * Every broker-specific fact (annotation FQNs, payload wrappers, producer
 * template) lives here so the Scanner/Selector/Walker/Extractor stay generic.
 *
 * All lookups are by string FQN: the broker libraries are intentionally NOT on
 * extractor-core's main classpath, so a broker silently no-ops when its types
 * are absent from the scanned ClassLoader.
 */
internal data class MessagingBroker(
    val id: String,
    val listenerAnnotation: String,
    /** Class-level multi-handler annotation (Kafka/Rabbit); null when unsupported. */
    val handlerAnnotation: String?,
    /** Generic wrappers to unwrap to a value-type argument (e.g. ConsumerRecord idx 1). */
    val recordWrappers: List<Wrapper>,
    /** Untyped parameter types treated as meta and ignored during payload selection. */
    val rawMetaTypes: List<String>,
    /** Producer template spec; null ⇒ listener-only (Spring Integration). */
    val producer: ProducerSpec?,
) {
    data class Wrapper(val fqn: String, val valueArgIndex: Int)

    companion object {
        val KAFKA = MessagingBroker(
            id = "kafka",
            listenerAnnotation = "org.springframework.kafka.annotation.KafkaListener",
            handlerAnnotation = "org.springframework.kafka.annotation.KafkaHandler",
            recordWrappers = listOf(Wrapper("org.apache.kafka.clients.consumer.ConsumerRecord", 1)),
            rawMetaTypes = listOf(
                "org.springframework.kafka.support.Acknowledgment",
                "org.apache.kafka.clients.consumer.Consumer",
            ),
            producer = ProducerSpec.GenericTemplate(
                fqn = "org.springframework.kafka.core.KafkaTemplate",
                valueArgIndex = 1,
                sendMethods = setOf("send"),
            ),
        )

        val JMS = MessagingBroker(
            id = "jms",
            listenerAnnotation = "org.springframework.jms.annotation.JmsListener",
            handlerAnnotation = null,
            recordWrappers = emptyList(),
            rawMetaTypes = listOf(
                "jakarta.jms.Message", "javax.jms.Message",
                "jakarta.jms.Session", "javax.jms.Session",
            ),
            producer = ProducerSpec.NonGenericTemplate(
                fqn = "org.springframework.jms.core.JmsTemplate",
                sendMethods = setOf("convertAndSend"),
            ),
        )

        val RABBIT = MessagingBroker(
            id = "rabbit",
            listenerAnnotation = "org.springframework.amqp.rabbit.annotation.RabbitListener",
            handlerAnnotation = "org.springframework.amqp.rabbit.annotation.RabbitHandler",
            recordWrappers = emptyList(),
            rawMetaTypes = listOf(
                "org.springframework.amqp.core.Message",
                "com.rabbitmq.client.Channel",
            ),
            producer = ProducerSpec.NonGenericTemplate(
                fqn = "org.springframework.amqp.rabbit.core.RabbitTemplate",
                sendMethods = setOf("convertAndSend"),
            ),
        )

        val PULSAR = MessagingBroker(
            id = "pulsar",
            listenerAnnotation = "org.springframework.pulsar.annotation.PulsarListener",
            handlerAnnotation = null,
            recordWrappers = listOf(
                Wrapper("org.apache.pulsar.client.api.Message", 0),
                Wrapper("org.apache.pulsar.client.api.Messages", 0),
            ),
            rawMetaTypes = listOf("org.apache.pulsar.client.api.Consumer"),
            producer = ProducerSpec.GenericTemplate(
                fqn = "org.springframework.pulsar.core.PulsarTemplate",
                valueArgIndex = 0,
                sendMethods = setOf("send", "sendAsync"),
            ),
        )

        val INTEGRATION = MessagingBroker(
            id = "integration",
            listenerAnnotation = "org.springframework.integration.annotation.ServiceActivator",
            handlerAnnotation = null,
            recordWrappers = emptyList(),
            rawMetaTypes = emptyList(),
            producer = null,
        )

        val ALL: List<MessagingBroker> = listOf(KAFKA, JMS, RABBIT, PULSAR, INTEGRATION)
    }
}

/** How a broker's producer template carries its payload type. */
internal sealed interface ProducerSpec {
    /** Payload type recoverable from the template field's generic signature (Kafka, Pulsar). */
    data class GenericTemplate(val fqn: String, val valueArgIndex: Int, val sendMethods: Set<String>) : ProducerSpec
    /** Non-generic template; payload type recovered from the send-call argument (JMS, Rabbit). */
    data class NonGenericTemplate(val fqn: String, val sendMethods: Set<String>) : ProducerSpec
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :extractor-core:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingBroker.kt
git commit -m "feat(messaging): add MessagingBroker descriptor and broker table"
```

---

## Task 3: Generalize the payload selector (TDD)

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingPayloadSelector.kt`
- Test: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingPayloadSelectorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingPayloadSelectorTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :extractor-core:test --tests "*MessagingPayloadSelectorTest*"`
Expected: FAIL — `MessagingPayloadSelector` unresolved (compilation error).

- [ ] **Step 3: Write the implementation**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingPayloadSelector.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.messaging

import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Picks the payload parameter of a listener method and unwraps framework
 * wrappers down to the value type. The shared rules (Spring `@Payload`/`@Header`,
 * `Message<T>`, `List<T>`) apply to every broker; broker-specific record
 * wrappers and untyped meta types come from the [MessagingBroker] descriptor.
 *
 * Broker libraries are not on the main classpath: meta/wrapper types are matched
 * by string FQN against the parameter's `Class.name`.
 */
internal object MessagingPayloadSelector {

    private const val PAYLOAD_ANNOTATION = "org.springframework.messaging.handler.annotation.Payload"
    private const val HEADER_ANNOTATION  = "org.springframework.messaging.handler.annotation.Header"
    private const val HEADERS_ANNOTATION = "org.springframework.messaging.handler.annotation.Headers"

    private const val MESSAGE_FQN = "org.springframework.messaging.Message"
    private const val LIST_FQN    = "java.util.List"

    sealed interface Result {
        /** Payload param picked; [payloadType] is post-unwrap. */
        data class Selected(val payloadType: Type) : Result
        data class Skipped(val reason: String) : Result
    }

    fun select(method: Method, broker: MessagingBroker): Result {
        val params = method.parameters.toList()
        if (params.isEmpty()) return Result.Skipped("no parameters")

        // 1. @Payload wins.
        val payloadAnnotated = params.firstOrNull { p ->
            p.annotations.any { it.annotationClass.java.name == PAYLOAD_ANNOTATION }
        }
        if (payloadAnnotated != null) return unwrap(payloadAnnotated.parameterizedType, broker)

        // 2. Exactly one non-meta parameter.
        val nonMeta = params.filter { !isMeta(it, broker) }
        return when (nonMeta.size) {
            1    -> unwrap(nonMeta.single().parameterizedType, broker)
            0    -> Result.Skipped("no payload parameter")
            else -> Result.Skipped("ambiguous payload parameter")
        }
    }

    private fun isMeta(p: Parameter, broker: MessagingBroker): Boolean {
        if (p.annotations.any {
                val n = it.annotationClass.java.name
                n == HEADER_ANNOTATION || n == HEADERS_ANNOTATION
            }) return true
        val raw = (p.parameterizedType as? Class<*>)
            ?: (p.parameterizedType as? ParameterizedType)?.rawType as? Class<*>
        val name = raw?.name ?: return false
        return name in broker.rawMetaTypes
    }

    private fun unwrap(t: Type, broker: MessagingBroker): Result {
        if (t is WildcardType) return Result.Skipped("wildcard payload")
        if (t is Class<*>) {
            // Raw (un-parameterized) wrapper → value type unrecoverable.
            if (t.name == MESSAGE_FQN || t.name == LIST_FQN || broker.recordWrappers.any { it.fqn == t.name }) {
                return Result.Skipped("raw ${t.simpleName} payload")
            }
            return Result.Selected(t)
        }
        if (t is ParameterizedType) {
            val raw = (t.rawType as? Class<*>) ?: return Result.Skipped("unrecognised payload type")
            broker.recordWrappers.firstOrNull { it.fqn == raw.name }?.let { w ->
                val arg = t.actualTypeArguments.getOrNull(w.valueArgIndex)
                    ?: return Result.Skipped("${raw.simpleName} without value type")
                return unwrap(arg, broker)
            }
            return when (raw.name) {
                MESSAGE_FQN -> unwrap(t.actualTypeArguments.getOrNull(0) ?: return Result.Skipped("Message without T"), broker)
                LIST_FQN    -> unwrap(t.actualTypeArguments.getOrNull(0) ?: return Result.Skipped("List without T"), broker)
                else        -> Result.Selected(t)
            }
        }
        return Result.Skipped("unrecognised payload type ${t.typeName}")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :extractor-core:test --tests "*MessagingPayloadSelectorTest*"`
Expected: PASS (all cases green).

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingPayloadSelector.kt extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingPayloadSelectorTest.kt
git commit -m "feat(messaging): broker-parameterized payload selector"
```

---

## Task 4: Generalize the listener scanner (TDD)

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingListenerScanner.kt`
- Test: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingListenerScannerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingListenerScannerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :extractor-core:test --tests "*MessagingListenerScannerTest*"`
Expected: FAIL — `MessagingListenerScanner` unresolved.

- [ ] **Step 3: Write the implementation**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingListenerScanner.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.messaging

import io.github.classgraph.ClassGraph
import java.lang.reflect.Method

/**
 * Discovers listener methods for a given [MessagingBroker]: method-level
 * `broker.listenerAnnotation`, plus class-level annotation + `broker.handlerAnnotation`
 * methods when the broker supports a multi-handler form (Kafka, Rabbit).
 *
 * String-based annotation lookup: returns an empty list when the broker's
 * annotations are absent from [classLoader].
 */
internal object MessagingListenerScanner {

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "org.apache",
    )

    /** A single listener method to extract a channel from. */
    data class Site(val ownerClass: Class<*>, val method: Method)

    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        broker: MessagingBroker,
        onWarn: (String) -> Unit = {},
    ): List<Site> {
        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            val methodSites = mutableListOf<Site>()

            val classes = result.getClassesWithMethodAnnotation(broker.listenerAnnotation)
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
            for (ci in classes) {
                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("messaging.${broker.id}.consumer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (mi in ci.methodInfo) {
                    if (!mi.hasAnnotation(broker.listenerAnnotation)) continue
                    val method = cls.declaredMethods.firstOrNull {
                        it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                    } ?: continue
                    methodSites += Site(cls, method)
                }
            }

            val handler = broker.handlerAnnotation
            if (handler != null) {
                val classLevel = result.getClassesWithAnnotation(broker.listenerAnnotation)
                    .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                    .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                for (ci in classLevel) {
                    val cls = try { ci.loadClass() } catch (t: Throwable) {
                        onWarn("messaging.${broker.id}.consumer: skipping ${ci.name}: ${t.message}")
                        continue
                    }
                    for (mi in ci.methodInfo) {
                        if (!mi.hasAnnotation(handler)) continue
                        val method = cls.declaredMethods.firstOrNull {
                            it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                        } ?: continue
                        methodSites += Site(cls, method)
                    }
                }
            }
            return methodSites
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :extractor-core:test --tests "*MessagingListenerScannerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingListenerScanner.kt extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingListenerScannerTest.kt
git commit -m "feat(messaging): broker-parameterized listener scanner"
```

---

## Task 5: Generalize the producer scanner (TDD)

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerScanner.kt`
- Test: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerScannerTest.kt`

`TemplateField.valueClass` is non-null for generic templates (recovered from field generics) and null for non-generic templates (resolved at the send site by the walker).

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerScannerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :extractor-core:test --tests "*MessagingProducerScannerTest*"`
Expected: FAIL — `MessagingProducerScanner` unresolved.

- [ ] **Step 3: Write the implementation**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerScanner.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.messaging

import io.github.classgraph.ClassGraph
import java.lang.reflect.ParameterizedType

/**
 * Finds classes holding a producer-template field for a given [MessagingBroker].
 *
 * For generic templates (Kafka, Pulsar) the concrete value type is recovered
 * from the field's generic signature at `valueArgIndex`. For non-generic
 * templates (JMS, Rabbit) only the field name is recorded; the value type is
 * resolved per send call site by [MessagingProducerWalker].
 *
 * Discovery is class-name-string based: returns empty when the template type is
 * absent from [classLoader], or when the broker is listener-only (`producer == null`).
 */
internal object MessagingProducerScanner {

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "org.apache",
    )

    /**
     * @property valueClass concrete value type for generic templates; null for
     *   non-generic templates (resolved at the send call site).
     */
    data class TemplateField(
        val ownerClass: Class<*>,
        val fieldName: String,
        val valueClass: Class<*>?,
    )

    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        broker: MessagingBroker,
        onWarn: (String) -> Unit = {},
    ): List<TemplateField> {
        val producer = broker.producer ?: return emptyList()
        val templateFqn = when (producer) {
            is ProducerSpec.GenericTemplate    -> producer.fqn
            is ProducerSpec.NonGenericTemplate -> producer.fqn
        }
        val genericValueIndex = (producer as? ProducerSpec.GenericTemplate)?.valueArgIndex

        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableFieldInfo()
            .ignoreFieldVisibility()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            val out = mutableListOf<TemplateField>()
            for (ci in result.allClasses) {
                if (FRAMEWORK_EXCLUSIONS.any { ci.name.startsWith("$it.") }) continue
                if (basePackage != null && !(ci.name.startsWith("$basePackage.") || ci.name == basePackage)) continue
                if (ci.fieldInfo.none { it.typeDescriptor?.toString() == templateFqn }) continue

                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("messaging.${broker.id}.producer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (field in cls.declaredFields) {
                    if (field.type.name != templateFqn) continue
                    if (genericValueIndex == null) {
                        out += TemplateField(cls, field.name, null)
                    } else {
                        val v = (field.genericType as? ParameterizedType)
                            ?.actualTypeArguments
                            ?.getOrNull(genericValueIndex) as? Class<*>
                        if (v == null || v == Any::class.java || v == java.lang.Object::class.java) {
                            onWarn("messaging.${broker.id}.producer: skipping ${cls.name}.${field.name}: template value type unresolved")
                            continue
                        }
                        out += TemplateField(cls, field.name, v)
                    }
                }
            }
            return out
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :extractor-core:test --tests "*MessagingProducerScannerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerScanner.kt extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerScannerTest.kt
git commit -m "feat(messaging): broker-parameterized producer scanner"
```

---

## Task 6: Generalize the producer bytecode walker (TDD)

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerWalker.kt`
- Test: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerWalkerTest.kt`

The walker handles two payload-recovery strategies: generic templates use the field's recovered value class; non-generic templates recover the payload type from the static type of the last value-producing instruction before the `convertAndSend` INVOKE.

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerWalkerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :extractor-core:test --tests "*MessagingProducerWalkerTest*"`
Expected: FAIL — `MessagingProducerWalker` unresolved.

- [ ] **Step 3: Write the implementation**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerWalker.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.messaging

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type as AsmType
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Discovers producer send-call sites in a class by linear bytecode scan.
 *
 * For each `INVOKE <sendMethod>` on the broker's template, the most recent
 * `GETFIELD <template>` identifies the field it was invoked on. The payload type
 * is then recovered:
 *  - Generic templates (Kafka, Pulsar): from the field's recovered value class.
 *  - Non-generic templates (JMS, Rabbit): best-effort from the static type of the
 *    last value-producing instruction before the INVOKE (local/param, field,
 *    method return, or `new X(...)`). Unresolvable / JDK-noise / MessagePostProcessor
 *    overloads are warn-skipped.
 *
 * Returns one [ProducerSite] per (enclosingMethod, valueClass) tuple.
 */
internal object MessagingProducerWalker {

    private const val MESSAGE_POST_PROCESSOR_SIMPLE = "MessagePostProcessor"

    data class ProducerSite(
        val ownerClass: Class<*>,
        val enclosingMethod: String,
        val valueClass: Class<*>,
    )

    fun walk(
        clazz: Class<*>,
        templateFields: List<MessagingProducerScanner.TemplateField>,
        broker: MessagingBroker,
        onWarn: (String) -> Unit = {},
    ): List<ProducerSite> {
        val producer = broker.producer ?: return emptyList()
        val fieldsForClass = templateFields.filter { it.ownerClass == clazz }
        if (fieldsForClass.isEmpty()) return emptyList()
        val fieldByName = fieldsForClass.associateBy { it.fieldName }

        val templateInternal = when (producer) {
            is ProducerSpec.GenericTemplate    -> producer.fqn.replace('.', '/')
            is ProducerSpec.NonGenericTemplate -> producer.fqn.replace('.', '/')
        }
        val templateDesc = "L$templateInternal;"
        val sendMethods = when (producer) {
            is ProducerSpec.GenericTemplate    -> producer.sendMethods
            is ProducerSpec.NonGenericTemplate -> producer.sendMethods
        }

        val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
        val cn = readClass(loader, classResource(clazz.name)) ?: return emptyList()

        val seen = linkedSetOf<ProducerSite>()
        for (m in cn.methods) {
            if ((m.access and Opcodes.ACC_BRIDGE) != 0) continue
            if ((m.access and Opcodes.ACC_SYNTHETIC) != 0) continue
            var lastTemplateField: String? = null
            var i: AbstractInsnNode? = m.instructions?.first
            while (i != null) {
                when (i) {
                    is FieldInsnNode -> if (i.opcode == Opcodes.GETFIELD && i.desc == templateDesc) {
                        lastTemplateField = i.name
                    }
                    is MethodInsnNode -> if (i.owner == templateInternal && i.name in sendMethods) {
                        val tf = lastTemplateField?.let(fieldByName::get)
                        if (tf != null) {
                            val value: Class<*>? = when (producer) {
                                is ProducerSpec.GenericTemplate -> tf.valueClass
                                is ProducerSpec.NonGenericTemplate ->
                                    resolvePayloadType(i, m, clazz, loader, onWarn)
                            }
                            if (value != null) seen += ProducerSite(clazz, m.name, value)
                        }
                    }
                }
                i = i.next
            }
        }
        return seen.toList()
    }

    /** Best-effort static-type recovery of the payload arg for a non-generic send. */
    private fun resolvePayloadType(
        invoke: MethodInsnNode,
        method: MethodNode,
        clazz: Class<*>,
        loader: ClassLoader,
        onWarn: (String) -> Unit,
    ): Class<*>? {
        // MessagePostProcessor overloads: payload is not the last arg → skip.
        if (AsmType.getArgumentTypes(invoke.desc).any { it.className.endsWith(".$MESSAGE_POST_PROCESSOR_SIMPLE") }) {
            onWarn("messaging.producer: skipping ${clazz.name}.${method.name}: MessagePostProcessor send overload not supported")
            return null
        }
        val prev = prevMeaningful(invoke.previous)
        val fqn: String? = when (prev) {
            is VarInsnNode ->
                if (prev.opcode == Opcodes.ALOAD) localType(method, prev.`var`) else null
            is FieldInsnNode ->
                if (prev.opcode == Opcodes.GETFIELD || prev.opcode == Opcodes.GETSTATIC)
                    AsmType.getType(prev.desc).objectClassName() else null
            is MethodInsnNode ->
                if (prev.name == "<init>" && prev.opcode == Opcodes.INVOKESPECIAL) prev.owner.replace('/', '.')
                else AsmType.getReturnType(prev.desc).objectClassName()
            else -> null
        }
        val resolved = fqn?.takeUnless { isNoise(it) } ?: run {
            onWarn("messaging.producer: skipping ${clazz.name}.${method.name}: payload type not recoverable")
            return null
        }
        return try {
            Class.forName(resolved, false, loader)
        } catch (t: Throwable) {
            onWarn("messaging.producer: skipping ${clazz.name}.${method.name}: cannot load $resolved: ${t.message}")
            null
        }
    }

    private fun AsmType.objectClassName(): String? = if (sort == AsmType.OBJECT) className else null

    private fun localType(method: MethodNode, varIndex: Int): String? {
        method.localVariables
            ?.firstOrNull { it.index == varIndex }
            ?.let { return AsmType.getType(it.desc).objectClassName() }
        // Fallback: parameter types from the method descriptor (slot 0 = `this` for instance methods).
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        val args = AsmType.getArgumentTypes(method.desc)
        val argIdx = if (isStatic) varIndex else varIndex - 1
        return args.getOrNull(argIdx)?.objectClassName()
    }

    private fun isNoise(fqn: String): Boolean =
        fqn.startsWith("java.") || fqn.startsWith("javax.") ||
            fqn.startsWith("jakarta.") || fqn.startsWith("kotlin.")

    private fun prevMeaningful(start: AbstractInsnNode?): AbstractInsnNode? {
        var n = start
        while (n != null && (n is LabelNode || n is LineNumberNode || n is FrameNode)) n = n.previous
        return n
    }

    private fun classResource(fqn: String): String = fqn.replace('.', '/') + ".class"

    private fun readClass(loader: ClassLoader, resource: String): ClassNode? {
        val stream = loader.getResourceAsStream(resource) ?: return null
        return stream.use {
            val reader = ClassReader(it)
            val node = ClassNode()
            reader.accept(node, ClassReader.SKIP_FRAMES)
            node
        }
    }
}
```

> **Note on `sendString` / `sendNew`:** `convertAndSend("orders", Order("n"))` compiles to `NEW Order; DUP; INVOKESPECIAL Order.<init>` immediately before the `convertAndSend` INVOKE, so `prevMeaningful` lands on the `<init>` and resolves `Order`. A `String` literal payload compiles to `LDC` (not handled) → resolves to null → warn-skip, which is the intended JDK-noise behavior.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :extractor-core:test --tests "*MessagingProducerWalkerTest*"`
Expected: PASS.

> If `sendField` resolves to null because the `LDC "orders"` (destination) sits between `GETFIELD cached` and the INVOKE: the bytecode order is `ALOAD this; GETFIELD jms; LDC "orders"; ALOAD this; GETFIELD cached; INVOKE` — so the payload `GETFIELD cached` IS the instruction immediately before INVOKE. The destination is pushed before the payload. This holds for all `convertAndSend([dest,] payload)` shapes.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerWalker.kt extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingProducerWalkerTest.kt
git commit -m "feat(messaging): producer walker with non-generic arg-type recovery"
```

---

## Task 7: Generalize the channel extractor

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingChannelExtractor.kt`

This is a direct generalization of `KafkaChannelExtractor` over the messaging site types. Behavior (PascalCase naming, multi-type disambiguation) is unchanged; coverage comes from the integration tests (Tasks 10–11), matching the original Kafka design which had no dedicated unit test for this class.

- [ ] **Step 1: Write the implementation**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingChannelExtractor.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.messaging

import community.flock.wirespec.spring.extractor.extract.TypeExtractor
import community.flock.wirespec.spring.extractor.model.Channel

/**
 * Turns messaging scan results into [Channel] domain values. Payload selection
 * is delegated to [MessagingPayloadSelector]; the resolved JVM type is converted
 * by [TypeExtractor] so existing DTO/enum/refined logic is reused.
 *
 * Channel naming matches HTTP/Kafka: PascalCase(method name) for consumers,
 * PascalCase(enclosing method) for producers (with a value-type suffix when one
 * method sends multiple types).
 */
internal class MessagingChannelExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    /** Build channels from consumer (listener) sites for the given broker. */
    fun fromListenerSites(sites: List<MessagingListenerScanner.Site>, broker: MessagingBroker): List<Channel> =
        sites.mapNotNull { site ->
            when (val r = MessagingPayloadSelector.select(site.method, broker)) {
                is MessagingPayloadSelector.Result.Selected -> Channel(
                    ownerSimpleName = site.ownerClass.simpleName,
                    name = pascalCase(site.method.name),
                    payload = types.extract(r.payloadType),
                )
                is MessagingPayloadSelector.Result.Skipped -> {
                    onWarn("messaging.${broker.id}.consumer: skipped ${site.ownerClass.name}.${site.method.name}: ${r.reason}")
                    null
                }
            }
        }

    /** Build channels from producer (send) sites. */
    fun fromProducerSites(sites: List<MessagingProducerWalker.ProducerSite>): List<Channel> {
        val byMethodKey = sites.groupBy { Triple(it.ownerClass, it.enclosingMethod, it.valueClass) }
            .keys.toList()
        val methodCounts = byMethodKey.groupingBy { it.first to it.second }.eachCount()
        return byMethodKey.map { (owner, methodName, valueClass) ->
            val base = pascalCase(methodName)
            val name = if ((methodCounts[owner to methodName] ?: 0) > 1) "${base}_${valueClass.simpleName}" else base
            Channel(
                ownerSimpleName = owner.simpleName,
                name = name,
                payload = types.extract(valueClass),
            )
        }
    }

    private fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :extractor-core:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/messaging/MessagingChannelExtractor.kt
git commit -m "feat(messaging): broker-parameterized channel extractor"
```

---

## Task 8: Wire the messaging loop into WirespecExtractor

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt`

Replace the Kafka-specific imports and the Kafka block (lines ~110–145) with a single loop over `MessagingBroker.ALL`. The old `extract.kafka` package is still present and compiles; it is deleted in Task 9.

- [ ] **Step 1: Swap the imports**

In `WirespecExtractor.kt`, replace these four import lines:

```kotlin
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaChannelExtractor
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaListenerScanner
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaProducerBytecodeWalker
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaProducerScanner
```

with:

```kotlin
import community.flock.wirespec.spring.extractor.extract.messaging.MessagingBroker
import community.flock.wirespec.spring.extractor.extract.messaging.MessagingChannelExtractor
import community.flock.wirespec.spring.extractor.extract.messaging.MessagingListenerScanner
import community.flock.wirespec.spring.extractor.extract.messaging.MessagingProducerScanner
import community.flock.wirespec.spring.extractor.extract.messaging.MessagingProducerWalker
```

- [ ] **Step 2: Replace the Kafka block with the broker loop**

In `WirespecExtractor.kt`, replace the entire block from the `// -- Kafka consumers ---...` comment through the end of the `// -- Kafka producers ---...` block (the code currently spanning roughly lines 110–145, ending just before `val byControllerFinal = ...`) with:

```kotlin
            // -- Messaging channels (Kafka, JMS, Rabbit, Pulsar, Spring Integration) ----
            val messagingExtractor = MessagingChannelExtractor(types, onWarn = { msg -> config.log.warn(msg) })
            for (broker in MessagingBroker.ALL) {
                val listenerSites = MessagingListenerScanner.scan(
                    loader, scanPackages, effectiveBasePackage, broker,
                    onWarn = { msg -> config.log.warn(msg) },
                )
                if (listenerSites.isNotEmpty()) {
                    config.log.info("Found ${listenerSites.size} ${broker.id} listener method(s)")
                }
                for (channel in messagingExtractor.fromListenerSites(listenerSites, broker)) {
                    val ws = builder.toChannel(channel)
                    val key = channel.ownerSimpleName
                    byController[key] = byController[key].orEmpty() + (ws as Definition)
                }

                val templateFields = MessagingProducerScanner.scan(
                    loader, scanPackages, effectiveBasePackage, broker,
                    onWarn = { msg -> config.log.warn(msg) },
                )
                if (templateFields.isNotEmpty()) {
                    config.log.info("Found ${templateFields.size} ${broker.id} template field(s)")
                }
                val producerOwners = templateFields.map { it.ownerClass }.distinct()
                val producerSites = producerOwners.flatMap { owner ->
                    MessagingProducerWalker.walk(owner, templateFields, broker, onWarn = { msg -> config.log.warn(msg) })
                }
                for (channel in messagingExtractor.fromProducerSites(producerSites)) {
                    val ws = builder.toChannel(channel)
                    val key = channel.ownerSimpleName
                    byController[key] = byController[key].orEmpty() + (ws as Definition)
                }
            }
```

- [ ] **Step 3: Run the full extractor-core unit suite**

Run: `./gradlew :extractor-core:test`
Expected: PASS. The existing Kafka unit tests (still present in `extract/kafka/`) and the new messaging tests both pass.

- [ ] **Step 4: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt
git commit -m "feat(messaging): drive extraction from the MessagingBroker table"
```

---

## Task 9: Delete the old Kafka package and its tests

**Files:**
- Delete: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/` (all 5 files)
- Delete: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/` (all 4 test files)

The generalized package fully replaces the Kafka-specific one; nothing references `extract.kafka` after Task 8.

- [ ] **Step 1: Confirm there are no remaining references**

Run: `grep -rn "extract.kafka\|KafkaListenerScanner\|KafkaPayloadSelector\|KafkaProducerScanner\|KafkaProducerBytecodeWalker\|KafkaChannelExtractor" extractor-core/src`
Expected: no output (zero matches).

- [ ] **Step 2: Delete the directories**

```bash
git rm -r extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka
git rm -r extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka
```

- [ ] **Step 3: Run the full unit suite**

Run: `./gradlew :extractor-core:test`
Expected: PASS (messaging tests cover the generalized behavior; nothing references the deleted package).

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(messaging): remove Kafka-specific package, superseded by extract.messaging"
```

---

## Task 10: Gradle integration test — combined `messaging-app` fixture

**Files:**
- Create: `integration-tests-gradle/src/it/messaging-app/settings.gradle.kts`
- Create: `integration-tests-gradle/src/it/messaging-app/build.gradle.kts`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/Events.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/JmsConsumer.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/JmsPublisher.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/RabbitConsumer.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/RabbitPublisher.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/PulsarConsumer.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/PulsarPublisher.kt`
- Create: `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/IntegrationConsumer.kt`
- Create: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/MessagingFixtureVerifier.kt`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt`

**Naming note:** each listener/producer method name is globally unique (per-broker prefix) because channel names are deduplicated globally across all `.ws` files (see `[[wirespec-names-globally-unique]]`); unique method names keep the asserted channel names stable.

- [ ] **Step 1: Write the fixture settings file**

Create `integration-tests-gradle/src/it/messaging-app/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("@itRepo@") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("@itRepo@") }
    }
}

rootProject.name = "messaging-app"
```

- [ ] **Step 2: Write the fixture build file**

Create `integration-tests-gradle/src/it/messaging-app/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework:spring-jms:6.1.14")
    implementation("org.springframework.amqp:spring-rabbit:3.1.7")
    implementation("org.springframework.pulsar:spring-pulsar:1.0.8")
    implementation("org.springframework.integration:spring-integration-core:6.2.7")
    implementation("org.springframework:spring-messaging:6.1.14")
    implementation("org.springframework:spring-context:6.1.14")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
```

- [ ] **Step 3: Write the shared event DTOs**

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/Events.kt`:

```kotlin
package com.acme.api

data class OrderEvent(
    val id: String,
    val customerId: String,
)

data class ShipmentEvent(
    val id: String,
    val carrier: String,
)
```

- [ ] **Step 4: Write the JMS consumer and publisher**

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/JmsConsumer.kt`:

```kotlin
package com.acme.api

import org.springframework.jms.annotation.JmsListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class JmsConsumer {

    @JmsListener(destination = "orders.created")
    fun onJmsOrderCreated(event: OrderEvent) {}

    @JmsListener(destination = "orders.with-header")
    fun onJmsOrderWithHeader(@Payload event: OrderEvent, @Header("source") source: String) {}
}
```

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/JmsPublisher.kt`:

```kotlin
package com.acme.api

import org.springframework.jms.core.JmsTemplate
import org.springframework.stereotype.Component

@Component
class JmsPublisher(private val jms: JmsTemplate) {

    fun publishJmsOrder(event: OrderEvent) {
        jms.convertAndSend("orders.created", event)
    }
}
```

- [ ] **Step 5: Write the Rabbit consumer and publisher**

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/RabbitConsumer.kt`:

```kotlin
package com.acme.api

import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

@Component
class RabbitConsumer {

    @RabbitListener(queues = ["orders.created"])
    fun onRabbitOrderCreated(event: OrderEvent) {}

    @RabbitListener(queues = ["orders.message"])
    fun onRabbitOrderMessage(message: Message<OrderEvent>) {}
}
```

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/RabbitPublisher.kt`:

```kotlin
package com.acme.api

import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component

@Component
class RabbitPublisher(private val rabbit: RabbitTemplate) {

    fun publishRabbitShipment(event: ShipmentEvent) {
        rabbit.convertAndSend("shipments", "shipments.created", event)
    }
}
```

- [ ] **Step 6: Write the Pulsar consumer and publisher**

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/PulsarConsumer.kt`:

```kotlin
package com.acme.api

import org.apache.pulsar.client.api.Message
import org.springframework.pulsar.annotation.PulsarListener
import org.springframework.stereotype.Component

@Component
class PulsarConsumer {

    @PulsarListener(topics = ["orders.created"])
    fun onPulsarOrderCreated(event: OrderEvent) {}

    @PulsarListener(topics = ["orders.message"])
    fun onPulsarOrderMessage(message: Message<OrderEvent>) {}
}
```

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/PulsarPublisher.kt`:

```kotlin
package com.acme.api

import org.springframework.pulsar.core.PulsarTemplate
import org.springframework.stereotype.Component

@Component
class PulsarPublisher(private val pulsar: PulsarTemplate<ShipmentEvent>) {

    fun publishPulsarShipment(event: ShipmentEvent) {
        pulsar.send("shipments.created", event)
    }
}
```

- [ ] **Step 7: Write the Spring Integration consumer (listener-only)**

Create `integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/IntegrationConsumer.kt`:

```kotlin
package com.acme.api

import org.springframework.integration.annotation.ServiceActivator
import org.springframework.stereotype.Component

@Component
class IntegrationConsumer {

    @ServiceActivator(inputChannel = "orders.in")
    fun onIntegrationOrder(event: OrderEvent) {}
}
```

- [ ] **Step 8: Write the verifier**

Create `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/MessagingFixtureVerifier.kt`:

```kotlin
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
```

- [ ] **Step 9: Register the fixture in the dispatch**

In `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt`, in the `when (fixture.name) { ... }` block in `runFixture`, add this line immediately after the `"kafka-app" -> ...` line:

```kotlin
            "messaging-app"    -> MessagingFixtureVerifier.verify(File(workDir, "build/wirespec"))
```

- [ ] **Step 10: Run the Gradle integration tests**

Run: `./gradlew :integration-tests-gradle:test --tests "*GradleFixtureBuildTest*"`
Expected: PASS — the `messaging-app` dynamic test (and existing `kafka-app`) are green. If a broker dependency fails to resolve, update the version in build.gradle.kts per the version-pin table.

- [ ] **Step 11: Commit**

```bash
git add integration-tests-gradle/src/it/messaging-app integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/MessagingFixtureVerifier.kt integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt
git commit -m "test(it): combined JMS/Rabbit/Pulsar/Integration messaging fixture (Gradle)"
```

---

## Task 11: Maven integration test — combined `messaging-app` fixture

**Files:**
- Create: `integration-tests-maven/src/it/messaging-app/pom.xml`
- Create: `integration-tests-maven/src/it/messaging-app/invoker.properties`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/Events.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/JmsConsumer.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/JmsPublisher.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/RabbitConsumer.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/RabbitPublisher.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/PulsarConsumer.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/PulsarPublisher.kt`
- Create: `integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/IntegrationConsumer.kt`
- Create: `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/MessagingFixtureVerifier.kt`
- Modify: `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/FixtureBuildTest.kt`

- [ ] **Step 1: Write the fixture pom**

Create `integration-tests-maven/src/it/messaging-app/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>messaging-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <kotlin.version>2.1.20</kotlin.version>
        <spring.version>6.1.14</spring.version>
        <spring-amqp.version>3.1.7</spring-amqp.version>
        <spring-pulsar.version>1.0.8</spring-pulsar.version>
        <spring-integration.version>6.2.7</spring-integration.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
            <version>${kotlin.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-jms</artifactId>
            <version>${spring.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.amqp</groupId>
            <artifactId>spring-rabbit</artifactId>
            <version>${spring-amqp.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.pulsar</groupId>
            <artifactId>spring-pulsar</artifactId>
            <version>${spring-pulsar.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.integration</groupId>
            <artifactId>spring-integration-core</artifactId>
            <version>${spring-integration.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-messaging</artifactId>
            <version>${spring.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <version>${spring.version}</version>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>process-sources</phase>
                        <goals><goal>compile</goal></goals>
                    </execution>
                </executions>
                <configuration>
                    <jvmTarget>21</jvmTarget>
                    <args>
                        <arg>-java-parameters</arg>
                    </args>
                </configuration>
            </plugin>

            <plugin>
                <groupId>community.flock.wirespec.spring</groupId>
                <artifactId>wirespec-spring-extractor-maven-plugin</artifactId>
                <version>@project.version@</version>
                <extensions>true</extensions>
                <configuration>
                    <output>${project.build.directory}/wirespec</output>
                    <basePackage>com.acme.api</basePackage>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write the invoker properties**

Create `integration-tests-maven/src/it/messaging-app/invoker.properties`:

```properties
invoker.goals = clean package
```

- [ ] **Step 3: Copy the fixture sources from the Gradle fixture**

The eight Kotlin source files are byte-identical to the Gradle fixture's `src/main/kotlin/com/acme/api/` files (Task 10, Steps 3–7). Create each of these with the exact same content as its Gradle counterpart:

```bash
mkdir -p integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api
cp integration-tests-gradle/src/it/messaging-app/src/main/kotlin/com/acme/api/*.kt \
   integration-tests-maven/src/it/messaging-app/src/main/kotlin/com/acme/api/
```

Files copied: `Events.kt`, `JmsConsumer.kt`, `JmsPublisher.kt`, `RabbitConsumer.kt`, `RabbitPublisher.kt`, `PulsarConsumer.kt`, `PulsarPublisher.kt`, `IntegrationConsumer.kt`.

- [ ] **Step 4: Write the Maven verifier**

Create `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/MessagingFixtureVerifier.kt` with the same assertions as the Gradle verifier (Task 10, Step 8), but in package `community.flock.wirespec.spring.extractor.it`:

```kotlin
package community.flock.wirespec.spring.extractor.it

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
```

- [ ] **Step 5: Register the fixture in the Maven dispatch**

In `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/FixtureBuildTest.kt`, in the `when (fixture.name) { ... }` block in `runFixture`, add this line immediately after the `"kafka-app" -> ...` line:

```kotlin
            "messaging-app"    -> MessagingFixtureVerifier.verify(File(workDir, "target/wirespec"))
```

- [ ] **Step 6: Run the Maven integration tests**

Run: `./gradlew :integration-tests-maven:test --tests "*FixtureBuildTest*"`
Expected: PASS — the `messaging-app` dynamic test (and existing `kafka-app`) are green. (Requires `mvn` on PATH / `MAVEN_HOME`, as the existing Maven IT already does.)

- [ ] **Step 7: Commit**

```bash
git add integration-tests-maven/src/it/messaging-app integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/MessagingFixtureVerifier.kt integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/FixtureBuildTest.kt
git commit -m "test(it): combined JMS/Rabbit/Pulsar/Integration messaging fixture (Maven)"
```

---

## Task 12: Full build verification

**Files:** none (verification only).

- [ ] **Step 1: Run the entire build and test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all unit tests and both integration-test modules pass.

- [ ] **Step 2: Confirm no stray references to the old package**

Run: `grep -rn "extract.kafka" --include=*.kt .`
Expected: no output.

- [ ] **Step 3: Final commit (if anything was adjusted)**

```bash
git add -A
git commit -m "chore(messaging): finalize external messaging listener extraction" || echo "nothing to finalize"
```

---

## Self-Review

**Spec coverage:**
- Broker generalization into descriptors → Tasks 2, 8 (`MessagingBroker.ALL` loop). ✓
- JMS / Rabbit / Pulsar / Integration listeners → Tasks 4, 10, 11 (scanner + fixtures). ✓
- Shared payload selection (`@Payload`/`@Header`/`Message<T>`/`List<T>`) + per-broker wrappers/meta → Task 3. ✓
- Generic-template producers (Kafka, Pulsar) → Tasks 5, 6 (field-generic path). ✓
- Non-generic best-effort producers (JMS, Rabbit) via send-arg type → Task 6. ✓
- Spring Integration listener-only (`producer == null`) → Tasks 2, 5 (scan returns empty), 10/11 (no publisher). ✓
- `extract.kafka` → `extract.messaging` rename → Tasks 2–9. ✓
- One combined `messaging-app` fixture per IT module → Tasks 10, 11. ✓
- Test-classpath/catalog deps off the main classpath → Task 1. ✓
- Out-of-scope items (MessagePostProcessor overloads, Integration outbound, Pulsar builder chains, raw wrappers) → handled as warn-skips in Tasks 3 & 6; not asserted positively. ✓

**Placeholder scan:** No TBD/TODO; every code step contains complete content. Version pins flagged as "verify at implementation time" with a fallback procedure (not a placeholder — concrete starting values given). ✓

**Type consistency:** `MessagingBroker` / `ProducerSpec.GenericTemplate` / `ProducerSpec.NonGenericTemplate` / `MessagingProducerScanner.TemplateField(ownerClass, fieldName, valueClass: Class<*>?)` / `MessagingProducerWalker.ProducerSite` / `MessagingListenerScanner.Site` / `MessagingPayloadSelector.Result.{Selected,Skipped}` / `MessagingChannelExtractor.{fromListenerSites(sites, broker), fromProducerSites(sites)}` are used identically across Tasks 2–8. The `scan(...)` signatures take `broker` as the 4th positional arg consistently. ✓
