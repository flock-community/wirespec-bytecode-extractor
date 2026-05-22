# Spring Kafka extraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Discover Spring Kafka consumers (`@KafkaListener`) and producers (`KafkaTemplate.send` call sites) in a Spring Boot application's compiled classes, and emit them as Wirespec `channel` definitions alongside existing HTTP endpoints.

**Architecture:** A sibling extraction pipeline runs against the same `ClassGraph` scan as the HTTP pipeline. New `KafkaListenerScanner` and `KafkaProducerScanner` discover sites; `KafkaChannelExtractor` produces `model.Channel`; `WirespecAstBuilder.toChannel` maps to the existing Wirespec `Channel` AST; the existing `Emitter` writes them with no changes. Spring Kafka is NOT a runtime dependency of extractor-core — all framework class names are string constants, scanned via ClassGraph and ASM, so the extractor cleanly no-ops on projects that don't use Spring Kafka.

**Tech Stack:** Kotlin 2.0-compatible (extractor-core pins `apiVersion = KOTLIN_2_0`), ClassGraph for annotation discovery, ASM for bytecode walking, JUnit 5 + Kotest assertions, Gradle TestKit for integration.

**Spec:** `docs/superpowers/specs/2026-05-22-kafka-listeners-design.md`

---

## File Structure

**Created:**
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/model/Channel.kt` — internal channel model
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt` — discovers `@KafkaListener` and `@KafkaHandler` methods
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelector.kt` — pure function: pick the payload parameter and unwrap wrappers
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScanner.kt` — finds classes with `KafkaTemplate` fields, recovers `V` from signature
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalker.kt` — walks methods for `INVOKEVIRTUAL KafkaTemplate.send`
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaChannelExtractor.kt` — orchestrates consumer + producer sites → `model.Channel`
- `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScannerTest.kt`
- `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelectorTest.kt`
- `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScannerTest.kt`
- `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalkerTest.kt`
- `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilderChannelTest.kt`
- `integration-tests-gradle/src/it/kafka-app/` — full fixture (settings + build + main sources)
- `integration-tests-maven/src/it/kafka-app/` — Maven mirror of the fixture
- `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt`

**Modified:**
- `extractor-core/build.gradle.kts` — add `testImplementation("org.springframework.kafka:spring-kafka:3.2.4")` so test code can reference real Spring Kafka annotations/types
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/model/Channel.kt` (new — listed above)
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilder.kt` — add `toChannel(model.Channel): WsChannel`
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ownership/TypeOwnership.kt` — recognise `WsChannel` in the reachability walk and as an owner of types
- `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt` — invoke Kafka scanners, merge channels into `byController`
- `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractorTest.kt` — add a no-Kafka smoke test (existing flow keeps working)
- `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt` — register `kafka-app` verifier
- `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/FixtureBuildTest.kt` and `DslFixtureVerifiers.kt` (or add `KafkaFixtureVerifier.kt` in the same package) — same registration on the Maven side

---

## Constants used throughout

These string constants represent Spring Kafka types that extractor-core MUST NOT import. They appear in scanners, bytecode walkers, and tests. Keep them in sync if a task asks you to reference one.

```kotlin
// Annotations
const val KAFKA_LISTENER_ANNOTATION = "org.springframework.kafka.annotation.KafkaListener"
const val KAFKA_HANDLER_ANNOTATION  = "org.springframework.kafka.annotation.KafkaHandler"
const val PAYLOAD_ANNOTATION        = "org.springframework.messaging.handler.annotation.Payload"
const val HEADER_ANNOTATION         = "org.springframework.messaging.handler.annotation.Header"
const val HEADERS_ANNOTATION        = "org.springframework.messaging.handler.annotation.Headers"

// Wrapper types (for unwrap rules)
const val CONSUMER_RECORD_FQN = "org.apache.kafka.clients.consumer.ConsumerRecord"
const val MESSAGE_FQN         = "org.springframework.messaging.Message"

// Meta parameter types (skipped during single-non-meta-param selection)
const val ACKNOWLEDGMENT_FQN = "org.springframework.kafka.support.Acknowledgment"
const val CONSUMER_FQN       = "org.apache.kafka.clients.consumer.Consumer"

// Producer
const val KAFKA_TEMPLATE_FQN          = "org.springframework.kafka.core.KafkaTemplate"
const val KAFKA_TEMPLATE_INTERNAL     = "org/springframework/kafka/core/KafkaTemplate"
const val PRODUCER_RECORD_FQN         = "org.apache.kafka.clients.producer.ProducerRecord"
```

---

## Task 1: Wire Spring Kafka into test classpath

**Files:**
- Modify: `extractor-core/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add Spring Kafka version + library declaration**

Open `gradle/libs.versions.toml`. Under `[versions]` add:

```toml
spring-kafka = "3.2.4"
```

Under `[libraries]` add:

```toml
spring-kafka = { module = "org.springframework.kafka:spring-kafka", version.ref = "spring-kafka" }
```

- [ ] **Step 2: Add the test dependency to extractor-core**

In `extractor-core/build.gradle.kts`, append to the existing `dependencies { }` block (after `testImplementation(libs.spring.webmvc)`):

```kotlin
    // Spring Kafka is referenced ONLY from test code (real annotations/types
    // for the scanner tests). It is deliberately not on the main classpath:
    // extractor-core's scanners use string-based class-name lookups so the
    // extractor cleanly no-ops on projects that don't use Spring Kafka.
    testImplementation(libs.spring.kafka)
```

- [ ] **Step 3: Verify the build resolves**

```bash
./gradlew :extractor-core:compileTestKotlin
```

Expected: BUILD SUCCESSFUL. No code changes yet — this just proves the dependency resolves.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml extractor-core/build.gradle.kts
git commit -m "chore(extractor): add spring-kafka as test dependency"
```

---

## Task 2: Add `model.Channel` and round-trip test

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/model/Channel.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilder.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilderChannelTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilderChannelTest.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Channel as WsChannel
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.spring.extractor.model.Channel
import community.flock.wirespec.spring.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class WirespecAstBuilderChannelTest {

    private val builder = WirespecAstBuilder()

    @Test
    fun `channel with custom payload reference`() {
        val ch = Channel(
            ownerSimpleName = "OrderConsumer",
            name = "OnOrderCreated",
            payload = WireType.Ref("OrderEvent"),
        )

        val ws = builder.toChannel(ch)

        ws.shouldBeInstanceOf<WsChannel>()
        ws.identifier.value shouldBe "OnOrderCreated"
        ws.annotations shouldBe emptyList()
        ws.reference.shouldBeInstanceOf<Reference.Custom>()
        (ws.reference as Reference.Custom).value shouldBe "OrderEvent"
    }

    @Test
    fun `channel with primitive payload reference`() {
        val ch = Channel(
            ownerSimpleName = "X",
            name = "OnPing",
            payload = WireType.Primitive(WireType.Primitive.Kind.STRING),
        )

        val ws = builder.toChannel(ch)
        ws.reference.shouldBeInstanceOf<Reference.Primitive>()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :extractor-core:test --tests "*WirespecAstBuilderChannelTest" --info
```

Expected: COMPILATION FAILURE — `Channel` model and `WirespecAstBuilder.toChannel` don't exist yet.

- [ ] **Step 3: Add `model.Channel`**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/model/Channel.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.model

/**
 * Internal domain model for a Wirespec channel. Parallel to [Endpoint], but
 * carries a single payload reference (the Wirespec `Channel` AST is single-payload).
 *
 * @property ownerSimpleName Simple name of the class that owns this channel — drives
 *   the per-class .ws file grouping used by the emitter.
 * @property name PascalCase identifier used as the Wirespec definition name.
 * @property payload Body type of the channel (consumer payload or producer value type).
 */
data class Channel(
    val ownerSimpleName: String,
    val name: String,
    val payload: WireType,
)
```

- [ ] **Step 4: Add `WirespecAstBuilder.toChannel`**

Open `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilder.kt`.

Add this import to the import block (alphabetical):

```kotlin
import community.flock.wirespec.compiler.core.parse.ast.Channel as WsChannel
```

Add this import:

```kotlin
import community.flock.wirespec.spring.extractor.model.Channel
```

Add this method to the `WirespecAstBuilder` class, immediately after `toEndpoint(...)`:

```kotlin
fun toChannel(c: Channel): WsChannel = WsChannel(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(c.name),
    reference = toReference(c.payload),
)
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :extractor-core:test --tests "*WirespecAstBuilderChannelTest"
```

Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/model/Channel.kt \
        extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilder.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilderChannelTest.kt
git commit -m "feat(extractor): add Channel model and AST mapping"
```

---

## Task 3: Teach `TypeOwnership` about `WsChannel`

Channels reference payload types. Without this, payload DTOs reachable only from channels won't be assigned to a channel's owning file — they'd be flagged as "no owning controller" and forced into `types.ws`. We want the same single-owner-inlines, multi-owner-floats behaviour as endpoints.

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ownership/TypeOwnership.kt`
- Modify (extend): `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/ownership/TypeOwnershipTest.kt` if it exists; otherwise no test changes here — Task 7's integration test exercises the path.

- [ ] **Step 1: Check whether an ownership test exists**

```bash
find extractor-core/src/test -name "TypeOwnership*Test.kt"
```

If a test file exists, add a unit test there mirroring an existing case but with a `WsChannel` instead of `WsEndpoint`. If not, skip to Step 2 — Task 7 (integration) will cover it.

- [ ] **Step 2: Add a `customNamesIn(WsChannel)` overload**

Open `TypeOwnership.kt`. Add this import (at the existing import block):

```kotlin
import community.flock.wirespec.compiler.core.parse.ast.Channel as WsChannel
```

Just below the existing `customNamesIn(endpoint: WsEndpoint)` helper, add:

```kotlin
/** Names of every `Reference.Custom` reachable from a channel's payload reference. */
internal fun customNamesIn(channel: WsChannel): Set<String> =
    customNamesIn(channel.reference).toSet()
```

- [ ] **Step 3: Include channels in the per-owner reachability frontier**

In `TypeOwnership.partition(...)`, find this block:

```kotlin
val reachable: Map<String, Set<String>> = endpointsByController.mapValues { (_, defs) ->
    val visited = linkedSetOf<String>()
    val frontier = ArrayDeque<String>()
    defs.filterIsInstance<WsEndpoint>().forEach { ep ->
        customNamesIn(ep).forEach { name ->
            if (visited.add(name)) frontier += name
        }
    }
    ...
```

Add a sibling `filterIsInstance<WsChannel>()` block right after the existing endpoint loop, before the `while (frontier.isNotEmpty())` loop:

```kotlin
defs.filterIsInstance<WsChannel>().forEach { ch ->
    customNamesIn(ch).forEach { name ->
        if (visited.add(name)) frontier += name
    }
}
```

- [ ] **Step 4: Run existing tests to verify nothing regressed**

```bash
./gradlew :extractor-core:test
```

Expected: all existing tests pass (the new channel path is dormant until later tasks emit channels).

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/ownership/TypeOwnership.kt
git commit -m "feat(extractor): include channels in type-ownership reachability"
```

---

## Task 4: `KafkaPayloadSelector` (pure logic) with all unwrap rules

This is the heart of consumer extraction. Isolated from any scanning so it can be exhaustively unit-tested.

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelector.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelectorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelectorTest.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.Acknowledgment
import org.springframework.messaging.Message
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import java.lang.reflect.Type
import java.lang.reflect.ParameterizedType
import org.junit.jupiter.api.Test

class KafkaPayloadSelectorTest {

    data class OrderEvent(val id: String)

    // -- Fixture methods. Their parameter types/annotations are what the
    // -- selector reads via reflection.
    class Fixtures {
        fun plain(event: OrderEvent) {}
        fun withRecord(rec: ConsumerRecord<String, OrderEvent>) {}
        fun withMessage(msg: Message<OrderEvent>) {}
        fun batch(events: List<OrderEvent>) {}
        fun withPayloadAnnotation(@Payload event: OrderEvent, @Header("k") key: String) {}
        fun nonMetaSinglePlusHeader(event: OrderEvent, @Header("k") key: String) {}
        fun ambiguousTwoPayloads(a: OrderEvent, b: OrderEvent) {}
        fun metaOnly(ack: Acknowledgment, consumer: Consumer<String, OrderEvent>) {}
    }

    private val cls = Fixtures::class.java

    private fun method(name: String) = cls.declaredMethods.first { it.name == name }

    @Test fun `plain single param`() {
        val r = KafkaPayloadSelector.select(method("plain"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Selected>()
        (r.payloadType as Class<*>) shouldBe OrderEvent::class.java
    }

    @Test fun `unwraps ConsumerRecord generic to V`() {
        val r = KafkaPayloadSelector.select(method("withRecord"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Selected>()
        (r.payloadType as Class<*>) shouldBe OrderEvent::class.java
    }

    @Test fun `unwraps Message generic to T`() {
        val r = KafkaPayloadSelector.select(method("withMessage"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Selected>()
        (r.payloadType as Class<*>) shouldBe OrderEvent::class.java
    }

    @Test fun `unwraps List for batch listener`() {
        val r = KafkaPayloadSelector.select(method("batch"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Selected>()
        (r.payloadType as Class<*>) shouldBe OrderEvent::class.java
    }

    @Test fun `Payload annotation wins over other params`() {
        val r = KafkaPayloadSelector.select(method("withPayloadAnnotation"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Selected>()
        (r.payloadType as Class<*>) shouldBe OrderEvent::class.java
    }

    @Test fun `single non-meta param when others are Header annotated`() {
        val r = KafkaPayloadSelector.select(method("nonMetaSinglePlusHeader"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Selected>()
        (r.payloadType as Class<*>) shouldBe OrderEvent::class.java
    }

    @Test fun `ambiguous returns Skipped`() {
        val r = KafkaPayloadSelector.select(method("ambiguousTwoPayloads"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Skipped>()
        (r as KafkaPayloadSelector.Result.Skipped).reason shouldBe "ambiguous payload parameter"
    }

    @Test fun `meta-only returns Skipped`() {
        val r = KafkaPayloadSelector.select(method("metaOnly"))
        r.shouldBeInstanceOf<KafkaPayloadSelector.Result.Skipped>()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :extractor-core:test --tests "*KafkaPayloadSelectorTest"
```

Expected: COMPILATION FAILURE — `KafkaPayloadSelector` does not exist.

- [ ] **Step 3: Implement `KafkaPayloadSelector`**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelector.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Pure logic: given a `@KafkaListener` (or `@KafkaHandler`) method, pick the
 * payload parameter and unwrap framework wrappers down to the value type.
 *
 * Spring Kafka is NOT on extractor-core's main classpath. We identify
 * meta parameters and framework wrappers by string FQN comparison against
 * the parameter's `Class.name`.
 */
internal object KafkaPayloadSelector {

    private const val PAYLOAD_ANNOTATION = "org.springframework.messaging.handler.annotation.Payload"
    private const val HEADER_ANNOTATION  = "org.springframework.messaging.handler.annotation.Header"
    private const val HEADERS_ANNOTATION = "org.springframework.messaging.handler.annotation.Headers"

    private const val CONSUMER_RECORD_FQN = "org.apache.kafka.clients.consumer.ConsumerRecord"
    private const val MESSAGE_FQN         = "org.springframework.messaging.Message"

    private const val ACKNOWLEDGMENT_FQN = "org.springframework.kafka.support.Acknowledgment"
    private const val CONSUMER_FQN       = "org.apache.kafka.clients.consumer.Consumer"

    sealed interface Result {
        /** Payload param picked; [payloadType] is post-unwrap. */
        data class Selected(val payloadType: Type) : Result
        data class Skipped(val reason: String) : Result
    }

    fun select(method: Method): Result {
        val params = method.parameters.toList()
        if (params.isEmpty()) return Result.Skipped("no parameters")

        // 1. @Payload wins.
        val payloadAnnotated = params.firstOrNull { p ->
            p.annotations.any { it.annotationClass.java.name == PAYLOAD_ANNOTATION }
        }
        if (payloadAnnotated != null) return unwrap(payloadAnnotated.parameterizedType)

        // 2. Exactly one non-meta parameter.
        val nonMeta = params.filter { !isMeta(it) }
        return when (nonMeta.size) {
            1    -> unwrap(nonMeta.single().parameterizedType)
            0    -> Result.Skipped("no payload parameter")
            else -> Result.Skipped("ambiguous payload parameter")
        }
    }

    private fun isMeta(p: Parameter): Boolean {
        if (p.annotations.any {
                val n = it.annotationClass.java.name
                n == HEADER_ANNOTATION || n == HEADERS_ANNOTATION
            }) return true
        val raw = (p.parameterizedType as? Class<*>) ?: (p.parameterizedType as? ParameterizedType)?.rawType as? Class<*>
        val name = raw?.name ?: return false
        return name == ACKNOWLEDGMENT_FQN || name == CONSUMER_FQN
    }

    private fun unwrap(t: Type): Result {
        if (t is WildcardType) return Result.Skipped("wildcard payload")
        if (t is Class<*>) {
            // Raw List/ConsumerRecord/Message with no type args → cannot recover V.
            if (t.name == CONSUMER_RECORD_FQN || t.name == MESSAGE_FQN || t.name == "java.util.List") {
                return Result.Skipped("raw ${t.simpleName} payload")
            }
            return Result.Selected(t)
        }
        if (t is ParameterizedType) {
            val raw = (t.rawType as? Class<*>) ?: return Result.Skipped("unrecognised payload type")
            return when (raw.name) {
                CONSUMER_RECORD_FQN -> unwrap(t.actualTypeArguments.getOrNull(1) ?: return Result.Skipped("ConsumerRecord without V"))
                MESSAGE_FQN         -> unwrap(t.actualTypeArguments.getOrNull(0) ?: return Result.Skipped("Message without T"))
                "java.util.List"    -> unwrap(t.actualTypeArguments.getOrNull(0) ?: return Result.Skipped("List without T"))
                else                -> Result.Selected(t)
            }
        }
        return Result.Skipped("unrecognised payload type ${t.typeName}")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :extractor-core:test --tests "*KafkaPayloadSelectorTest"
```

Expected: 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelector.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaPayloadSelectorTest.kt
git commit -m "feat(extractor): add Kafka payload selector with wrapper unwrapping"
```

---

## Task 5: `KafkaListenerScanner` (method-level discovery)

This task only covers method-level `@KafkaListener`. Class-level `@KafkaListener` + `@KafkaHandler` is added later in Task 9, so the verifier here intentionally ignores that shape.

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScannerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScannerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :extractor-core:test --tests "*KafkaListenerScannerTest"
```

Expected: COMPILATION FAILURE — `KafkaListenerScanner` does not exist.

- [ ] **Step 3: Implement the scanner**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import io.github.classgraph.ClassGraph
import java.lang.reflect.Method

/**
 * Discovers `@KafkaListener` listener methods in the user's compiled classes.
 *
 * Covers method-level `@KafkaListener`. Class-level `@KafkaListener` +
 * `@KafkaHandler` discovery is added separately (see [scanKafkaHandlers]).
 *
 * String-based class-name lookup: Spring Kafka is intentionally NOT on this
 * module's main classpath, so the scanner cleanly returns an empty list when
 * `org.springframework.kafka.*` is absent from [classLoader].
 */
internal object KafkaListenerScanner {

    private const val KAFKA_LISTENER_ANNOTATION = "org.springframework.kafka.annotation.KafkaListener"
    private const val KAFKA_HANDLER_ANNOTATION  = "org.springframework.kafka.annotation.KafkaHandler"

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
            val classes = result.getClassesWithMethodAnnotation(KAFKA_LISTENER_ANNOTATION)
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }

            for (ci in classes) {
                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("kafka.consumer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (mi in ci.methodInfo) {
                    if (!mi.hasAnnotation(KAFKA_LISTENER_ANNOTATION)) continue
                    val method = cls.declaredMethods.firstOrNull {
                        it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                    } ?: continue
                    methodSites += Site(cls, method)
                }
            }
            return methodSites
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :extractor-core:test --tests "*KafkaListenerScannerTest"
```

Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScannerTest.kt
git commit -m "feat(extractor): scan method-level @KafkaListener"
```

---

## Task 6: `KafkaChannelExtractor` (consumer side only)

Wires `KafkaListenerScanner.Site` → `KafkaPayloadSelector` → `TypeExtractor` → `model.Channel`. Producer integration is added in Task 11.

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaChannelExtractor.kt`

- [ ] **Step 1: Implement the extractor**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaChannelExtractor.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import community.flock.wirespec.spring.extractor.extract.TypeExtractor
import community.flock.wirespec.spring.extractor.model.Channel

/**
 * Turns Kafka scan results into [Channel] domain values. Payload selection is
 * delegated to [KafkaPayloadSelector]; the resolved JVM [java.lang.reflect.Type]
 * is then converted by [TypeExtractor] so existing DTO/enum/refined logic is
 * reused (Jackson naming, validation constraints, generic flattening, nullability).
 *
 * Channel naming = PascalCase(method name), matching the existing endpoint
 * naming convention used by HTTP extraction.
 */
internal class KafkaChannelExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    /** Build channels from consumer (listener) sites. */
    fun fromListenerSites(sites: List<KafkaListenerScanner.Site>): List<Channel> =
        sites.mapNotNull { site ->
            when (val r = KafkaPayloadSelector.select(site.method)) {
                is KafkaPayloadSelector.Result.Selected -> Channel(
                    ownerSimpleName = site.ownerClass.simpleName,
                    name = pascalCase(site.method.name),
                    payload = types.extract(r.payloadType),
                )
                is KafkaPayloadSelector.Result.Skipped -> {
                    onWarn("kafka.consumer: skipped ${site.ownerClass.name}.${site.method.name}: ${r.reason}")
                    null
                }
            }
        }

    private fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
}
```

- [ ] **Step 2: No test for this orchestration class yet — integration test in Task 7 covers it end-to-end**

This is intentional: the orchestration is one line per branch, and the branches are unit-tested via `KafkaPayloadSelector` and `TypeExtractor`. The integration test in the next task is the most useful confirmation.

- [ ] **Step 3: Compile check**

```bash
./gradlew :extractor-core:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaChannelExtractor.kt
git commit -m "feat(extractor): orchestrate Kafka listener sites into Channels"
```

---

## Task 7: Wire consumers into `WirespecExtractor` and add minimal integration fixture

This is the first end-to-end test — actually emitting `channel` lines into a `.ws` file.

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt`
- Create: `integration-tests-gradle/src/it/kafka-app/settings.gradle.kts`
- Create: `integration-tests-gradle/src/it/kafka-app/build.gradle.kts`
- Create: `integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderEvent.kt`
- Create: `integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderConsumer.kt`
- Create: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt`

- [ ] **Step 1: Wire the consumer pipeline into `WirespecExtractor`**

Open `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt`.

Add imports (alphabetical):

```kotlin
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaChannelExtractor
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaListenerScanner
```

Inside the `ClasspathBuilder.fromUrls(...).use { loader -> ... }` block, just after the existing DSL `for (cfg in dslConfigs) { ... }` loop and the `byControllerFinal` assignment, but BEFORE the `allTypes`/`partition`/`Emitter().write(...)` calls, insert:

```kotlin
            // -- Kafka consumers --------------------------------------------------
            val listenerSites = KafkaListenerScanner.scan(
                loader, scanPackages, effectiveBasePackage,
                onWarn = { msg -> config.log.warn(msg) },
            )
            if (listenerSites.isNotEmpty()) {
                config.log.info("Found ${listenerSites.size} @KafkaListener method(s)")
            }
            val kafkaExtractor = KafkaChannelExtractor(types, onWarn = { msg -> config.log.warn(msg) })
            val consumerChannels = kafkaExtractor.fromListenerSites(listenerSites)
            for (channel in consumerChannels) {
                val ws = builder.toChannel(channel)
                val key = channel.ownerSimpleName
                val existing = byController[key].orEmpty()
                byController[key] = existing + (ws as Definition)
            }
```

Then move the `val byControllerFinal = byController.filterValues { it.isNotEmpty() }` line so it comes AFTER this new Kafka block (i.e. delete the earlier occurrence and place it just below the new code).

> Note: `byController` is already declared as `mutableMapOf`; reuse it. The existing line `val byControllerFinal = byController.filterValues { it.isNotEmpty() }` was the last statement before `allTypes`; that placement must now be after the Kafka block.

- [ ] **Step 2: Verify extractor-core still compiles and existing tests pass**

```bash
./gradlew :extractor-core:test
```

Expected: all existing tests pass.

- [ ] **Step 3: Create the fixture's Gradle skeleton**

`integration-tests-gradle/src/it/kafka-app/settings.gradle.kts`:

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

rootProject.name = "kafka-app"
```

`integration-tests-gradle/src/it/kafka-app/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework.kafka:spring-kafka:3.2.4")
    implementation("org.springframework:spring-messaging:6.1.14")
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

- [ ] **Step 4: Create the fixture's source files (minimal — one listener, one DTO)**

`integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderEvent.kt`:

```kotlin
package com.acme.api

data class OrderEvent(
    val id: String,
    val customerId: String,
)
```

`integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderConsumer.kt`:

```kotlin
package com.acme.api

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderConsumer {

    @KafkaListener(topics = ["orders.created"])
    fun onOrderCreated(event: OrderEvent) {
        // no-op: integration fixture
    }
}
```

- [ ] **Step 5: Create the verifier**

`integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt`:

```kotlin
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
```

- [ ] **Step 6: Register the fixture in the build test**

Open `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt`.

Find the `when (fixture.name) { ... }` block in `runFixture`. Add this case (alphabetical order, after `dsl-webflux-app`):

```kotlin
            "kafka-app"        -> KafkaFixtureVerifier.verify(File(workDir, "build/wirespec"))
```

- [ ] **Step 7: Run the integration test for just this fixture**

```bash
./gradlew :integration-tests-gradle:test --tests "*GradleFixtureBuildTest*kafka-app*" --info
```

Expected: PASS. If the channel emission line uses different syntax than `channel <Name> -> <Type>`, update the verifier — `WirespecEmitter` is the source of truth; check its output and match.

> If you need to inspect the emitter's actual output format, run the test once with the verifier deliberately failing (e.g., assert `shouldContain "ZZZ"`), copy the rendered `.ws` from the failure output, then update the assertion to match the real format.

- [ ] **Step 8: Run the full extractor-core and gradle IT suites to confirm no regression**

```bash
./gradlew :extractor-core:test :integration-tests-gradle:test
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt \
        integration-tests-gradle/src/it/kafka-app \
        integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt \
        integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/GradleFixtureBuildTest.kt
git commit -m "feat(extractor): emit Wirespec channels for @KafkaListener consumers"
```

---

## Task 8: Cover all consumer parameter shapes in the fixture

Tests the unwrap rules end-to-end (`ConsumerRecord`, `Message`, `List` batch, `@Payload + @Header`).

**Files:**
- Modify: `integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderConsumer.kt`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt`

- [ ] **Step 1: Add the additional listener methods**

Replace `OrderConsumer.kt`'s body with:

```kotlin
package com.acme.api

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.Message
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class OrderConsumer {

    @KafkaListener(topics = ["orders.created"])
    fun onOrderCreated(event: OrderEvent) {}

    @KafkaListener(topics = ["orders.record"])
    fun onOrderRecord(record: ConsumerRecord<String, OrderEvent>) {}

    @KafkaListener(topics = ["orders.message"])
    fun onOrderMessage(message: Message<OrderEvent>) {}

    @KafkaListener(topics = ["orders.batch"])
    fun onOrderBatch(events: List<OrderEvent>) {}

    @KafkaListener(topics = ["orders.with-header"])
    fun onOrderWithHeader(@Payload event: OrderEvent, @Header("source") source: String) {}
}
```

- [ ] **Step 2: Extend the verifier**

Update `KafkaFixtureVerifier.verify(...)` so its consumer-block assertions become:

```kotlin
        consumer shouldContain "channel OnOrderCreated -> OrderEvent"
        consumer shouldContain "channel OnOrderRecord -> OrderEvent"
        consumer shouldContain "channel OnOrderMessage -> OrderEvent"
        consumer shouldContain "channel OnOrderBatch -> OrderEvent"
        consumer shouldContain "channel OnOrderWithHeader -> OrderEvent"
        consumer shouldContain "type OrderEvent"
```

- [ ] **Step 3: Run the integration test**

```bash
./gradlew :integration-tests-gradle:test --tests "*GradleFixtureBuildTest*kafka-app*" --info
```

Expected: PASS. Five channels in the output.

- [ ] **Step 4: Commit**

```bash
git add integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderConsumer.kt \
        integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt
git commit -m "test(it): cover all @KafkaListener parameter shapes"
```

---

## Task 9: Class-level `@KafkaListener` + `@KafkaHandler` support

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScannerTest.kt`
- Create (fixture): `integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/ShipmentEvent.kt`
- Create (fixture): `integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/ShipmentRouter.kt`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt`

- [ ] **Step 1: Add the failing scanner test for class-level discovery**

Append to `KafkaListenerScannerTest.kt`:

```kotlin
    @org.springframework.kafka.annotation.KafkaListener(topics = ["shipments"])
    @Suppress("unused")
    class ShipmentRouter {
        @org.springframework.kafka.annotation.KafkaHandler
        fun onCreated(event: Order) {}

        @org.springframework.kafka.annotation.KafkaHandler
        fun onUpdated(event: Order) {}
    }

    @Test
    fun `discovers @KafkaHandler methods under class-level @KafkaListener`() {
        val loader = javaClass.classLoader
        val sites = KafkaListenerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("community.flock.wirespec.spring.extractor.extract.kafka"),
            basePackage = null,
        )
        val ours = sites.filter { it.ownerClass == ShipmentRouter::class.java }
        ours shouldHaveSize 2
        ours.map { it.method.name }.toSet() shouldBe setOf("onCreated", "onUpdated")
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :extractor-core:test --tests "*KafkaListenerScannerTest"
```

Expected: the new test FAILS (returns 0 sites) — class-level isn't recognised yet.

- [ ] **Step 3: Extend the scanner**

In `KafkaListenerScanner.scan(...)`, after the existing method-level discovery block (just before `return methodSites`), add:

```kotlin
            val classLevel = result.getClassesWithAnnotation(KAFKA_LISTENER_ANNOTATION)
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
            for (ci in classLevel) {
                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("kafka.consumer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (mi in ci.methodInfo) {
                    if (!mi.hasAnnotation(KAFKA_HANDLER_ANNOTATION)) continue
                    val method = cls.declaredMethods.firstOrNull {
                        it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                    } ?: continue
                    methodSites += Site(cls, method)
                }
            }
```

- [ ] **Step 4: Run scanner tests to confirm they pass**

```bash
./gradlew :extractor-core:test --tests "*KafkaListenerScannerTest"
```

Expected: 2 tests pass.

- [ ] **Step 5: Extend the integration fixture**

`integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/ShipmentEvent.kt`:

```kotlin
package com.acme.api

data class ShipmentEvent(val id: String, val status: String)
```

`integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/ShipmentRouter.kt`:

```kotlin
package com.acme.api

import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@KafkaListener(topics = ["shipments"])
class ShipmentRouter {

    @KafkaHandler
    fun onShipmentCreated(event: ShipmentEvent) {}

    @KafkaHandler
    fun onOrderShipped(event: OrderEvent) {}
}
```

- [ ] **Step 6: Extend the verifier**

In `KafkaFixtureVerifier.verify(...)`, change the `files.shouldContainExactly(...)` to include the new file, and add per-file assertions:

```kotlin
        files.shouldContainExactly("OrderConsumer.ws", "ShipmentRouter.ws", "types.ws")

        // OrderEvent is now referenced by BOTH OrderConsumer.ws and
        // ShipmentRouter.ws — TypeOwnership should float it into types.ws.
        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type OrderEvent"

        val shipment = File(wsDir, "ShipmentRouter.ws").readText()
        shipment shouldContain "channel OnShipmentCreated -> ShipmentEvent"
        shipment shouldContain "channel OnOrderShipped -> OrderEvent"
        // ShipmentEvent has only one owner — it stays inline.
        shipment shouldContain "type ShipmentEvent"
```

Remove the now-incorrect line `consumer shouldContain "type OrderEvent"` from the consumer assertions block — `OrderEvent` floats to `types.ws` once it has multiple owners.

- [ ] **Step 7: Run the integration test**

```bash
./gradlew :integration-tests-gradle:test --tests "*GradleFixtureBuildTest*kafka-app*"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScanner.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaListenerScannerTest.kt \
        integration-tests-gradle/src/it/kafka-app \
        integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt
git commit -m "feat(extractor): support class-level @KafkaListener + @KafkaHandler"
```

---

## Task 10: `KafkaProducerScanner` — recover `V` from `KafkaTemplate` field signatures

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScanner.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScannerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScannerTest.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate

class KafkaProducerScannerTest {

    data class Order(val id: String)
    data class Shipment(val id: String)

    @Suppress("unused")
    class GoodPublisher(
        private val orders: KafkaTemplate<String, Order>,
        private val shipments: KafkaTemplate<String, Shipment>,
    )

    @Suppress("unused")
    class RawPublisher(private val raw: KafkaTemplate<*, *>)

    @Test
    fun `recovers V for typed KafkaTemplate fields`() {
        val sites = KafkaProducerScanner.scan(
            classLoader = javaClass.classLoader,
            scanPackages = listOf("community.flock.wirespec.spring.extractor.extract.kafka"),
            basePackage = null,
        )
        val good = sites.filter { it.ownerClass == GoodPublisher::class.java }
        good shouldHaveSize 2
        good.map { it.valueClass.simpleName }.toSet() shouldBe setOf("Order", "Shipment")
    }

    @Test
    fun `drops raw KafkaTemplate fields`() {
        val sites = KafkaProducerScanner.scan(
            classLoader = javaClass.classLoader,
            scanPackages = listOf("community.flock.wirespec.spring.extractor.extract.kafka"),
            basePackage = null,
        )
        sites.none { it.ownerClass == RawPublisher::class.java } shouldBe true
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :extractor-core:test --tests "*KafkaProducerScannerTest"
```

Expected: COMPILATION FAILURE — `KafkaProducerScanner` does not exist.

- [ ] **Step 3: Implement the scanner**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScanner.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import io.github.classgraph.ClassGraph
import java.lang.reflect.ParameterizedType

/**
 * Finds classes that hold (or accept) a `KafkaTemplate<K, V>` field and
 * recovers the concrete value type `V` per field via reflection on the
 * field's generic signature.
 *
 * Discovery is class-name-string based: Spring Kafka is intentionally not on
 * extractor-core's main classpath, so the scanner cleanly returns empty when
 * `KafkaTemplate` is absent.
 */
internal object KafkaProducerScanner {

    private const val KAFKA_TEMPLATE_FQN = "org.springframework.kafka.core.KafkaTemplate"

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "org.apache",
    )

    /**
     * @property ownerClass class declaring the KafkaTemplate field.
     * @property fieldName name of the KafkaTemplate field (used by the
     *   bytecode walker to match GETFIELD instructions).
     * @property valueClass concrete `V` recovered from the field's generic signature.
     */
    data class TemplateField(
        val ownerClass: Class<*>,
        val fieldName: String,
        val valueClass: Class<*>,
    )

    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        onWarn: (String) -> Unit = {},
    ): List<TemplateField> {
        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableFieldInfo()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            val out = mutableListOf<TemplateField>()
            for (ci in result.allClasses) {
                if (FRAMEWORK_EXCLUSIONS.any { ci.name.startsWith("$it.") }) continue
                if (basePackage != null && !(ci.name.startsWith("$basePackage.") || ci.name == basePackage)) continue
                // `typeDescriptor` is the erased descriptor; for `KafkaTemplate<K, V>` it
                // stringifies to the bare FQN regardless of generics, so equality suffices.
                if (ci.fieldInfo.none { it.typeDescriptor?.toString() == KAFKA_TEMPLATE_FQN }) continue

                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("kafka.producer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (field in cls.declaredFields) {
                    if (field.type.name != KAFKA_TEMPLATE_FQN) continue
                    val v = (field.genericType as? ParameterizedType)
                        ?.actualTypeArguments
                        ?.getOrNull(1) as? Class<*>
                    if (v == null || v == Any::class.java || v == java.lang.Object::class.java) {
                        onWarn("kafka.producer: skipping ${cls.name}.${field.name}: KafkaTemplate value type unresolved")
                        continue
                    }
                    out += TemplateField(cls, field.name, v)
                }
            }
            return out
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :extractor-core:test --tests "*KafkaProducerScannerTest"
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScanner.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerScannerTest.kt
git commit -m "feat(extractor): scan KafkaTemplate fields and recover value type"
```

---

## Task 11: `KafkaProducerBytecodeWalker` — find `send` call sites

Walks each class's methods looking for `INVOKEVIRTUAL KafkaTemplate.send`. Each call is attributed to the originating `KafkaTemplate` field (matched via the preceding `GETFIELD`) so the bytecode walker can return `(enclosingMethod, valueClass)` per call site.

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalker.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalkerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalkerTest.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate

class KafkaProducerBytecodeWalkerTest {

    data class Order(val id: String)
    data class Shipment(val id: String)

    @Suppress("unused")
    class Publisher(
        private val orders: KafkaTemplate<String, Order>,
        private val shipments: KafkaTemplate<String, Shipment>,
    ) {
        fun publishOrder(order: Order) {
            orders.send("orders.created", order)
        }

        fun publishOrderTwice(order: Order) {
            orders.send("orders.created", order)
            orders.send("orders.updated", order)
        }

        fun publishShipment(shipment: Shipment) {
            shipments.send("shipments.created", shipment)
        }

        fun noSend(order: Order) {
            // does not call send
        }
    }

    @Test
    fun `discovers one channel per (method, value-type) tuple`() {
        val fields = listOf(
            KafkaProducerScanner.TemplateField(Publisher::class.java, "orders", Order::class.java),
            KafkaProducerScanner.TemplateField(Publisher::class.java, "shipments", Shipment::class.java),
        )
        val sites = KafkaProducerBytecodeWalker.walk(Publisher::class.java, fields)
        sites shouldHaveSize 3
        sites.map { it.enclosingMethod to it.valueClass.simpleName }.toSet() shouldBe setOf(
            "publishOrder" to "Order",
            "publishOrderTwice" to "Order",
            "publishShipment" to "Shipment",
        )
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :extractor-core:test --tests "*KafkaProducerBytecodeWalkerTest"
```

Expected: COMPILATION FAILURE — `KafkaProducerBytecodeWalker` doesn't exist.

- [ ] **Step 3: Implement the walker**

Create `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalker.kt`:

```kotlin
package community.flock.wirespec.spring.extractor.extract.kafka

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Discovers `KafkaTemplate.send` call sites in a class by linear bytecode scan.
 *
 * For each `INVOKEVIRTUAL KafkaTemplate.send(...)`, the most recent `GETFIELD`
 * is inspected to identify which `KafkaTemplate` field it was invoked on. That
 * field is looked up in [templateFields] to recover the concrete value type `V`,
 * which becomes the channel payload.
 *
 * Returns one [ProducerSite] per (enclosingMethod, valueClass) pair —
 * duplicate calls within the same method collapse, calls across different
 * methods do not.
 *
 * Overloads taking `ProducerRecord` or `Message<?>` are recognised but
 * skipped via [onWarn], because their `V` is not on the KafkaTemplate field
 * — it would require call-site argument stack tracking, which is out of
 * scope for this iteration.
 */
internal object KafkaProducerBytecodeWalker {

    private const val KAFKA_TEMPLATE_INTERNAL = "org/springframework/kafka/core/KafkaTemplate"
    private const val PRODUCER_RECORD_INTERNAL = "org/apache/kafka/clients/producer/ProducerRecord"
    private const val MESSAGE_INTERNAL = "org/springframework/messaging/Message"

    data class ProducerSite(
        val ownerClass: Class<*>,
        val enclosingMethod: String,
        val valueClass: Class<*>,
    )

    fun walk(
        clazz: Class<*>,
        templateFields: List<KafkaProducerScanner.TemplateField>,
        onWarn: (String) -> Unit = {},
    ): List<ProducerSite> {
        val fieldsForClass = templateFields.filter { it.ownerClass == clazz }
        if (fieldsForClass.isEmpty()) return emptyList()
        val fieldByName = fieldsForClass.associateBy { it.fieldName }

        val cn = readClass(clazz.classLoader ?: ClassLoader.getSystemClassLoader(), classResource(clazz.name)) ?: return emptyList()

        val seen = linkedSetOf<ProducerSite>()
        for (m in cn.methods) {
            if ((m.access and Opcodes.ACC_BRIDGE) != 0) continue
            if ((m.access and Opcodes.ACC_SYNTHETIC) != 0) continue
            var lastTemplateField: String? = null
            var i: AbstractInsnNode? = m.instructions?.first
            while (i != null) {
                when (i) {
                    is FieldInsnNode -> if (i.opcode == Opcodes.GETFIELD && i.desc == "L$KAFKA_TEMPLATE_INTERNAL;") {
                        lastTemplateField = i.name
                    }
                    is MethodInsnNode -> if (i.opcode == Opcodes.INVOKEVIRTUAL && i.owner == KAFKA_TEMPLATE_INTERNAL && i.name == "send") {
                        // Reject overloads taking ProducerRecord or Message — the V type
                        // for those lives on the argument, not the field generic.
                        if (i.desc.contains("L$PRODUCER_RECORD_INTERNAL;") || i.desc.contains("L$MESSAGE_INTERNAL;")) {
                            onWarn("kafka.producer: skipping ${clazz.name}.${m.name}: ProducerRecord/Message send overload not yet supported")
                        } else {
                            val tf = lastTemplateField?.let(fieldByName::get)
                            if (tf != null) {
                                seen += ProducerSite(clazz, m.name, tf.valueClass)
                            }
                        }
                    }
                }
                i = i.next
            }
        }
        return seen.toList()
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

- [ ] **Step 4: Run tests**

```bash
./gradlew :extractor-core:test --tests "*KafkaProducerBytecodeWalkerTest"
```

Expected: 1 test passes (3 sites discovered).

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalker.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaProducerBytecodeWalkerTest.kt
git commit -m "feat(extractor): walk bytecode for KafkaTemplate.send call sites"
```

---

## Task 12: Producer orchestration + wiring into `WirespecExtractor` + fixture extension

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaChannelExtractor.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt`
- Create (fixture): `integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderPublisher.kt`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt`

- [ ] **Step 1: Extend `KafkaChannelExtractor` with producer orchestration**

Add this method to `KafkaChannelExtractor`, alongside the existing `fromListenerSites`:

```kotlin
    /** Build channels from producer (send) sites. */
    fun fromProducerSites(sites: List<KafkaProducerBytecodeWalker.ProducerSite>): List<Channel> {
        // Multiple sites with the same (ownerClass, enclosingMethod, valueClass)
        // were already collapsed by the walker; if the same enclosing method
        // produces different valueClasses, disambiguate via TypeSimpleName suffix.
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
```

- [ ] **Step 2: Wire producers into `WirespecExtractor`**

Open `WirespecExtractor.kt` and add imports:

```kotlin
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaProducerBytecodeWalker
import community.flock.wirespec.spring.extractor.extract.kafka.KafkaProducerScanner
```

Just after the consumer-channels block added in Task 7 (still BEFORE `byControllerFinal`), insert:

```kotlin
            // -- Kafka producers --------------------------------------------------
            val templateFields = KafkaProducerScanner.scan(
                loader, scanPackages, effectiveBasePackage,
                onWarn = { msg -> config.log.warn(msg) },
            )
            if (templateFields.isNotEmpty()) {
                config.log.info("Found ${templateFields.size} KafkaTemplate field(s)")
            }
            val producerOwners = templateFields.map { it.ownerClass }.distinct()
            val producerSites = producerOwners.flatMap { owner ->
                KafkaProducerBytecodeWalker.walk(owner, templateFields, onWarn = { msg -> config.log.warn(msg) })
            }
            val producerChannels = kafkaExtractor.fromProducerSites(producerSites)
            for (channel in producerChannels) {
                val ws = builder.toChannel(channel)
                val key = channel.ownerSimpleName
                val existing = byController[key].orEmpty()
                byController[key] = existing + (ws as Definition)
            }
```

- [ ] **Step 3: Run extractor-core tests**

```bash
./gradlew :extractor-core:test
```

Expected: all pass.

- [ ] **Step 4: Add the producer to the integration fixture**

`integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderPublisher.kt`:

```kotlin
package com.acme.api

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class OrderPublisher(
    private val orders: KafkaTemplate<String, OrderEvent>,
    private val shipments: KafkaTemplate<String, ShipmentEvent>,
) {

    fun publishOrder(event: OrderEvent) {
        orders.send("orders.created", event)
    }

    fun publishShipment(event: ShipmentEvent) {
        shipments.send("shipments.created", event)
    }
}
```

- [ ] **Step 5: Extend the verifier**

Update `KafkaFixtureVerifier.verify(...)`:

```kotlin
        files.shouldContainExactly("OrderConsumer.ws", "OrderPublisher.ws", "ShipmentRouter.ws", "types.ws")

        val publisher = File(wsDir, "OrderPublisher.ws").readText()
        publisher shouldContain "channel PublishOrder -> OrderEvent"
        publisher shouldContain "channel PublishShipment -> ShipmentEvent"

        // ShipmentEvent now has two owners (ShipmentRouter + OrderPublisher) — it
        // should float to types.ws and disappear from ShipmentRouter.ws.
        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type ShipmentEvent"
        types shouldContain "type OrderEvent"

        val shipment = File(wsDir, "ShipmentRouter.ws").readText()
        assertTrue(!Regex("(?m)^\\s*type\\s+ShipmentEvent\\b").containsMatchIn(shipment)) {
            "ShipmentEvent leaked into ShipmentRouter.ws despite being shared:\n$shipment"
        }
```

You must remove the previous `shipment shouldContain "type ShipmentEvent"` assertion from Task 9 — `ShipmentEvent` now has two owners and floats out.

- [ ] **Step 6: Run the integration test**

```bash
./gradlew :integration-tests-gradle:test --tests "*GradleFixtureBuildTest*kafka-app*"
```

Expected: PASS.

- [ ] **Step 7: Full regression check**

```bash
./gradlew :extractor-core:test :integration-tests-gradle:test
```

Expected: every test passes.

- [ ] **Step 8: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/extract/kafka/KafkaChannelExtractor.kt \
        extractor-core/src/main/kotlin/community/flock/wirespec/spring/extractor/WirespecExtractor.kt \
        integration-tests-gradle/src/it/kafka-app/src/main/kotlin/com/acme/api/OrderPublisher.kt \
        integration-tests-gradle/src/test/kotlin/community/flock/wirespec/spring/extractor/it/gradle/KafkaFixtureVerifier.kt
git commit -m "feat(extractor): emit Wirespec channels for KafkaTemplate.send producers"
```

---

## Task 13: Maven integration fixture mirror

**Files:**
- Create: `integration-tests-maven/src/it/kafka-app/` (mirror of the Gradle fixture, Maven layout)
- Modify: `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/FixtureBuildTest.kt`
- Modify (or create alongside): `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/DslFixtureVerifiers.kt` to add a `verifyKafkaApp` entry, OR add a new `KafkaFixtureVerifier.kt` next to it.

- [ ] **Step 1: Inspect an existing Maven fixture to copy its `pom.xml` layout**

```bash
ls integration-tests-maven/src/it/dsl-mvc-app/
```

Read `integration-tests-maven/src/it/dsl-mvc-app/pom.xml`. Replicate its structure for `kafka-app`, swapping the Kotlin sources to match the Gradle fixture's sources (same package `com.acme.api`, same DTOs, same `OrderConsumer`/`ShipmentRouter`/`OrderPublisher`), and replacing `spring-webmvc` deps with `spring-kafka:3.2.4` and `spring-messaging:6.1.14`.

- [ ] **Step 2: Create the Maven fixture sources**

Use the exact same `.kt` files as the Gradle fixture from Task 12 (copy them into `integration-tests-maven/src/it/kafka-app/src/main/kotlin/com/acme/api/`).

- [ ] **Step 3: Register the verifier in the Maven `FixtureBuildTest`**

Open `integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it/FixtureBuildTest.kt` and find the dispatch matching fixture names. Add:

```kotlin
            "kafka-app" -> KafkaFixtureVerifier.verify(File(workDir, "target/wirespec"))
```

(Or use whatever import path matches where you placed the verifier — same logic as the Gradle side, but pointing at `target/wirespec` instead of `build/wirespec`.)

- [ ] **Step 4: Create / extend the Maven verifier**

Put the same verifier logic from `KafkaFixtureVerifier` (the Gradle one) into the Maven test sources — either as a new `KafkaFixtureVerifier.kt` or by adding a `verifyKafkaApp(wsDir: File)` function to the existing `DslFixtureVerifiers.kt`. The assertion body is identical to the Gradle verifier (after all Task 12 changes).

- [ ] **Step 5: Run the Maven IT for the new fixture**

```bash
./gradlew :integration-tests-maven:test --tests "*FixtureBuildTest*kafka-app*"
```

Expected: PASS. If the test infrastructure builds all fixtures together (it likely does — check `FixtureBuildTest`'s `@TestFactory`), just run the full IT:

```bash
./gradlew :integration-tests-maven:test
```

Expected: all fixtures pass.

- [ ] **Step 6: Full regression check**

```bash
./gradlew check
```

Expected: every test in every module passes.

- [ ] **Step 7: Commit**

```bash
git add integration-tests-maven/src/it/kafka-app \
        integration-tests-maven/src/test/kotlin/community/flock/wirespec/spring/extractor/it
git commit -m "test(it): mirror Kafka fixture in the Maven integration suite"
```

---

## Task 14: Document Kafka support in README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a "Kafka extraction" section**

Read the existing README to find the right insertion point — likely after the "Usage" sections and before any "Plugin resolution" / "Notes" section. Add:

```markdown
## Kafka extraction

In addition to HTTP endpoints, the extractor discovers Spring Kafka
listeners and producers and emits them as Wirespec `channel` definitions.

- **Consumers**: methods annotated with `@KafkaListener`, and methods
  annotated with `@KafkaHandler` inside a class-level `@KafkaListener`.
  The payload type is taken from the `@Payload` parameter, or the single
  non-meta parameter, with `ConsumerRecord<K, V>`, `Message<T>`, and
  `List<T>` (batch) unwrapped to the value type.

- **Producers**: methods that call `KafkaTemplate.send(...)`. The value
  type is recovered from the `KafkaTemplate<K, V>` field's generic
  signature. Each enclosing method produces one channel.

Channels are named after the handler/sender method (`onOrderCreated` →
`OnOrderCreated`) and grouped into the `.ws` file of the owning class —
the same convention used for HTTP endpoints. Topic names are not read;
they are typically property placeholders at extract time. Spring Kafka
does not need to be on the extractor's classpath — extraction cleanly
no-ops in projects that don't use it.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document Kafka listener and producer extraction"
```

---

## Self-Review Notes

**Spec coverage** — every spec section maps to a task:

| Spec section | Tasks |
|---|---|
| `model.Channel` | Task 2 |
| `WirespecAstBuilder.toChannel` | Task 2 |
| `TypeOwnership` for channels | Task 3 |
| `KafkaListenerScanner` (method-level) | Task 5 |
| `KafkaListenerScanner` (class-level + `@KafkaHandler`) | Task 9 |
| Payload extraction rules (consumer) | Task 4 |
| `KafkaProducerScanner` | Task 10 |
| `KafkaProducerBytecodeWalker` | Task 11 |
| `KafkaChannelExtractor` | Tasks 6 + 12 |
| Naming and file grouping | Tasks 6, 12 (`pascalCase`, `ownerSimpleName`) |
| Classpath gating (no Spring Kafka dep on extractor-core main) | Task 1 (test-only dep), enforced by string-based lookups in Tasks 5, 10, 11 |
| `ExtractLog.warn` skip paths | Throughout — every `onWarn(...)` call with a `kafka.consumer:` / `kafka.producer:` prefix |
| Unit tests | Tasks 2, 4, 5, 9, 10, 11 |
| Integration tests (gradle + maven) | Tasks 7, 8, 9, 12, 13 |
| Implementation ordering | This plan follows the spec's suggested order |

**Out-of-scope items from the spec are not implemented** (intentionally):
- `@SendTo` derived producer channels
- `send(ProducerRecord)` / `send(Message<?>)` overloads (recognised but skipped with a warn)
- Direction metadata on the channel
- Topic-name resolution to channel identifiers
