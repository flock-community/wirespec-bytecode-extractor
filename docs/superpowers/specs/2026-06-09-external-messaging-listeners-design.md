# Design: Extract messaging channels for JMS, RabbitMQ, Pulsar & Spring Integration

**Date:** 2026-06-09
**Status:** Approved (pending spec review)

## Goal

Extend the Spring → Wirespec extractor — which today extracts Kafka listeners and
`KafkaTemplate` producers into Wirespec `channel` definitions — to additionally extract
broker-backed messaging for **JMS** (`@JmsListener`), **RabbitMQ** (`@RabbitListener`),
**Apache Pulsar** (`@PulsarListener`), and **Spring Integration** (`@ServiceActivator`).

These four are external message brokers/endpoints whose typed payloads map naturally onto
Wirespec channels, exactly as Kafka does. In-process mechanisms (e.g. `@EventListener`) are
deliberately excluded — they are not external services and do not model as channels.

Scope: **listeners (consumers) for all four**, plus **producers** where a template-style send
model exists (full support for generic templates; best-effort for non-generic templates;
none for Spring Integration).

## Background — current Kafka pattern

Kafka extraction lives in `extractor-core/.../extract/kafka/` and runs as a block in
`WirespecExtractor.extract`:

- `KafkaListenerScanner` — ClassGraph scan for method-level `@KafkaListener` and class-level
  `@KafkaListener` + `@KafkaHandler`. String-FQN lookups, so it no-ops when Spring Kafka is
  absent from the user classpath.
- `KafkaPayloadSelector` — picks the payload param (`@Payload` wins; else single non-meta
  param) and unwraps `ConsumerRecord<K,V>`, Spring `Message<T>`, `List<T>` to the value type.
- `KafkaProducerScanner` — finds `KafkaTemplate<K,V>` fields and recovers `V` from the field's
  generic signature.
- `KafkaProducerBytecodeWalker` — linear bytecode scan pairing `GETFIELD KafkaTemplate` with
  `INVOKEVIRTUAL send(...)`; `V` comes from the field (arg-type tracking is explicitly out of
  scope there).
- `KafkaChannelExtractor` — turns sites into `Channel(ownerSimpleName, name, payload)`;
  channel name = `PascalCase(methodName)`; payload converted via the shared `TypeExtractor`.

Channels are merged into `byController` keyed by owner simple name, then partitioned by
`TypeOwnership` (shared types float to `types.ws`) and written by `Emitter`. Global
name-dedup already exists downstream and is untouched.

## Decisions (from brainstorming)

1. **Brokers:** JMS, RabbitMQ, Pulsar, Spring Integration.
2. **Scope:** listeners + producers (producers where a template model fits).
3. **Architecture:** generalize the Kafka components into broker-descriptor-driven
   "messaging" components. The `extract.kafka` package is **renamed** to `extract.messaging`;
   Kafka becomes one descriptor among five. No behavioral change to Kafka output.
4. **Producers:** full support for generic templates (Kafka, Pulsar) via field generics;
   best-effort for non-generic templates (JMS, Rabbit) via send-call argument static type;
   Spring Integration is listener-only.
5. **Fixtures:** one combined `messaging-app` fixture per IT module (Gradle + Maven),
   mirroring how `kafka-app` exists in both.

## Architecture

Rename `extractor-core/.../extract/kafka/` → `extractor-core/.../extract/messaging/` and
generalize each class to take a broker descriptor:

```
extract/messaging/
  MessagingBroker.kt            // descriptor data classes + ALL: List<MessagingBroker>
  MessagingListenerScanner.kt   // ← KafkaListenerScanner
  MessagingPayloadSelector.kt   // ← KafkaPayloadSelector
  MessagingProducerScanner.kt   // ← KafkaProducerScanner
  MessagingProducerWalker.kt    // ← KafkaProducerBytecodeWalker
  MessagingChannelExtractor.kt  // ← KafkaChannelExtractor
```

### Descriptor

```kotlin
internal data class MessagingBroker(
    val id: String,                       // "kafka" | "jms" | "rabbit" | "pulsar" | "integration"
    val listenerAnnotation: String,       // method- & class-level annotation FQN
    val handlerAnnotation: String?,       // class-level multi-handler (Kafka/Rabbit); null otherwise
    val recordWrappers: List<Wrapper>,    // generic wrappers to unwrap to a value type arg
    val rawMetaTypes: List<String>,       // untyped params treated as meta and ignored
    val producer: ProducerSpec?,          // null ⇒ listener-only
) {
    data class Wrapper(val fqn: String, val valueArgIndex: Int)
    companion object { val ALL: List<MessagingBroker> = listOf(/* kafka, jms, rabbit, pulsar, integration */) }
}

internal sealed interface ProducerSpec {
    data class GenericTemplate(val fqn: String, val valueArgIndex: Int, val sendMethods: Set<String>) : ProducerSpec
    data class NonGenericTemplate(val fqn: String, val sendMethods: Set<String>) : ProducerSpec
}
```

### Broker table (concrete FQNs)

| Broker | Listener annotation | Handler annotation | Producer template |
|---|---|---|---|
| kafka | `org.springframework.kafka.annotation.KafkaListener` | `org.springframework.kafka.annotation.KafkaHandler` | `org.springframework.kafka.core.KafkaTemplate` — generic, valueArgIndex 1, `send` |
| jms | `org.springframework.jms.annotation.JmsListener` | — | `org.springframework.jms.core.JmsTemplate` — non-generic, `convertAndSend` |
| rabbit | `org.springframework.amqp.rabbit.annotation.RabbitListener` | `org.springframework.amqp.rabbit.annotation.RabbitHandler` | `org.springframework.amqp.rabbit.core.RabbitTemplate` — non-generic, `convertAndSend` |
| pulsar | `org.springframework.pulsar.annotation.PulsarListener` | — | `org.springframework.pulsar.core.PulsarTemplate` — generic, valueArgIndex 0, `send`/`sendAsync` |
| integration | `org.springframework.integration.annotation.ServiceActivator` | — | none (listener-only) |

Per-broker payload config (in addition to the shared Spring rules below):

| Broker | recordWrappers | rawMetaTypes (ignored params) |
|---|---|---|
| kafka | `ConsumerRecord` idx 1 | `org.springframework.kafka.support.Acknowledgment`, `org.apache.kafka.clients.consumer.Consumer` |
| jms | — | `jakarta.jms.Message`, `javax.jms.Message`, `jakarta.jms.Session`, `javax.jms.Session` |
| rabbit | — | `org.springframework.amqp.core.Message`, `com.rabbitmq.client.Channel` |
| pulsar | `org.apache.pulsar.client.api.Message` idx 0, `org.apache.pulsar.client.api.Messages` idx 0 | `org.apache.pulsar.client.api.Consumer` |
| integration | — | — |

### Orchestration

`WirespecExtractor.extract` replaces the single Kafka block with a loop over
`MessagingBroker.ALL`. Per broker: scan listener sites → extract consumer channels; if
`producer != null`, scan producer sites → extract producer channels. Merge every channel into
`byController` keyed by `ownerSimpleName`, exactly as today. Info-level logs are emitted per
broker only when sites are found (so silent no-op for absent brokers stays quiet).

## Listener payload selection

`MessagingPayloadSelector.select(method, broker)` keeps the shared, broker-independent rules:

- `@Payload`-annotated param wins.
- Otherwise exactly one non-meta param (meta = `@Header`/`@Headers`-annotated, or a type in
  `broker.rawMetaTypes`); zero ⇒ `Skipped("no payload parameter")`, >1 ⇒
  `Skipped("ambiguous payload parameter")`.
- Unwrap shared Spring `org.springframework.messaging.Message<T>` (idx 0) and
  `java.util.List<T>` (idx 0), plus any `broker.recordWrappers`, to the value type arg.
- Raw/un-parameterized wrappers ⇒ `Skipped("raw <Type> payload")` with a warning (matches
  current Kafka behavior for raw `ConsumerRecord`).

## Producer detection

- **Generic templates (Kafka, Pulsar):** unchanged approach. `MessagingProducerScanner` finds
  template fields and recovers the value type from the field's generic signature at
  `valueArgIndex`. `MessagingProducerWalker` pairs `GETFIELD <template>` with
  `INVOKE* <sendMethod>` and uses the field's recovered value type as payload. Overloads whose
  payload is not on the field generic (e.g. `ProducerRecord`/`Message`) ⇒ warn-skip, as today.

- **Non-generic templates (JMS, Rabbit):** the walker recovers the payload type from the
  **static type of the last value-producing instruction immediately before the
  `convertAndSend` INVOKE** (left-to-right arg evaluation puts the payload last):
  - `ALOAD n` → local/parameter type via the method's local-variable table (params via method
    descriptor);
  - `GETFIELD`/`GETSTATIC` → field descriptor type;
  - `INVOKE*` with non-void return → return type;
  - `NEW X` / `INVOKESPECIAL X.<init>` → `X`.

  Resolved internal name → `Class` via the app classloader → `TypeExtractor`. Unresolvable,
  `java.lang.Object`, JDK-noise (`java.*`, `kotlin.*` primitives/String), or a
  `MessagePostProcessor`-bearing overload ⇒ warn-skip. Best-effort and documented as such.

- **Spring Integration:** `producer == null`; no producer scan, no per-class warning.

## Channel construction

`MessagingChannelExtractor` is the generalized `KafkaChannelExtractor`: consumer channel name =
`PascalCase(methodName)`; producer channel name = `PascalCase(enclosingMethod)`, disambiguated
by value-type simple-name suffix when one method sends multiple types. `ownerSimpleName` drives
per-class `.ws` grouping. Payload converted via the shared `TypeExtractor`. Downstream
`TypeOwnership` / `Emitter` / global name-dedup are untouched.

## Testing

### Unit tests (extractor-core)

The four Kafka unit tests move into `extract/messaging/` and generalize:

- `MessagingPayloadSelectorTest` — Kafka cases retained; add JMS (`@Payload`+`@Header`,
  single DTO, raw `jakarta.jms.Message` skipped), Rabbit (raw `amqp Message` skipped, spring
  `Message<T>` unwrap), Pulsar (`Message<T>` / `Messages<T>` unwrap), Integration
  (`@Payload` / `Message<T>`).
- `MessagingListenerScannerTest` — method-level for all brokers; class-level + handler for
  Kafka & Rabbit.
- `MessagingProducerScannerTest` — generic field value recovery for Kafka & Pulsar.
- `MessagingProducerWalkerTest` — generic path (Kafka/Pulsar) plus non-generic arg-type
  recovery (JMS/Rabbit): local, param, field, method-return, `new X()`; and warn-skip for
  unresolvable / `MessagePostProcessor` overloads.

Requires adding `spring-jms`, `spring-amqp`, `spring-pulsar`, `spring-integration-core` (and
their messaging deps) to extractor-core's **test** classpath and the Gradle version catalog
(`gradle/libs.versions.toml`). They stay off the main classpath, preserving the string-FQN
no-op behavior.

### Integration tests (Gradle + Maven IT modules)

One combined fixture `messaging-app` per module, under `com.acme.api`, depending on
spring-jms, spring-amqp, spring-pulsar, spring-integration + spring-messaging:

- `JmsConsumer` / `JmsPublisher`
- `RabbitConsumer` / `RabbitPublisher`
- `PulsarConsumer` / `PulsarPublisher`
- `IntegrationConsumer` (listener-only)
- shared `OrderEvent`, `ShipmentEvent` DTOs reused across brokers (so they float to
  `types.ws`, mirroring the Kafka fixture).

Each consumer exercises the listener variants (plain DTO, `@Payload`+`@Header`, spring
`Message<T>`, broker record wrapper where applicable). Each publisher exercises a
`convertAndSend`/`send` producer with a recoverable payload type.

A `MessagingFixtureVerifier` per IT module (ported like `KafkaFixtureVerifier`, reading
`build/wirespec` vs `target/wirespec`) asserts the expected `channel X -> Payload` lines in
each owner file and that shared DTOs land in `types.ws` and do not leak into per-class files.
Register the fixture/verifier in `GradleFixtureBuildTest` and the Maven `FixtureBuildTest`
dispatch.

## Out of scope / known limitations

- JMS/Rabbit `convertAndSend` with a `MessagePostProcessor`, or a payload whose static type is
  not recoverable by the heuristics above → warn-skipped, not extracted.
- Spring Integration outbound (messaging gateways, `MessageChannel.send`) → not modeled.
- Pulsar `newMessage(...)...send()` builder chains → only direct `send`/`sendAsync`.
- Raw, un-parameterized broker wrappers (e.g. raw `jakarta.jms.Message`) → skipped (no
  recoverable value type).

## Risks

- **Kafka regression:** the rename + generalization must keep Kafka output byte-identical. The
  existing `kafka-app` IT and the generalized unit tests are the guard.
- **Pulsar test deps:** `spring-pulsar` pulls a large `pulsar-client`. Acceptable for a
  compile-only fixture; if resolution proves flaky, the Pulsar slice can be split into its own
  fixture without changing the core design.
