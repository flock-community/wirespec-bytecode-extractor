# Spring Kafka extraction — design

**Date:** 2026-05-22
**Status:** Approved (brainstorming complete; ready for implementation plan)

## Goal

Extend `wirespec-bytecode-extractor` to discover Spring Kafka consumers and producers in a Spring Boot application's compiled classes and emit them as Wirespec `channel` definitions, alongside the existing HTTP `endpoint` extraction. No changes to plugin configuration surface, output directory, or HTTP behavior.

## Scope

In scope for this round:

- Consumers: `@org.springframework.kafka.annotation.KafkaListener` (method-level and class-level paired with `@KafkaHandler`).
- Producers: call sites of `org.springframework.kafka.core.KafkaTemplate.send(...)` discovered via bytecode walking, with the value type recovered from the `KafkaTemplate<K, V>` field generic signature.

Explicitly out of scope (documented as future extensions):

- Other messaging annotations: `@JmsListener`, `@RabbitListener`, Spring Cloud Stream `@StreamListener`.
- `@SendTo` on listener return values (derived producer channels).
- Producers passing `ProducerRecord<K, V>` or `Message<?>` to `send(...)` (requires bytecode stack tracking to recover `V`).
- Encoding direction (consumer vs producer) on the Wirespec channel — Wirespec `Channel` has no direction field; the listener/sender method name carries the convention.
- Resolving `${property}` topic placeholders or SpEL back to channel identifiers — channel names come from the handler method, not the topic string. Keys, headers, consumer groups, and IDs are ignored.

## Architecture

A sibling extraction pipeline runs alongside the HTTP pipeline against the same `ClassGraph` scan. Both produce `Definition` values keyed by owning class; the existing `Emitter` writes the combined map without modification.

```
ClasspathBuilder ── ClassGraph ──┬── ControllerScanner ──► EndpointExtractor ─┐
                                 │                                            ├─► WirespecAstBuilder ─► Emitter
                                 ├── kafka.KafkaListenerScanner ──┐           │
                                 └── kafka.KafkaProducerScanner ──┴► KafkaChannelExtractor ──┘
```

## New components

All under `extractor-core/src/main/kotlin/community/flock/wirespec/bytecode/extractor/extract/kafka/` unless noted.

### `model.Channel` (new, under `model/`)

Parallel to `model.Endpoint`. Single-payload because the Wirespec `Channel` AST is single-payload.

```kotlin
data class Channel(
    val ownerSimpleName: String,
    val name: String,        // Wirespec definition name, PascalCase
    val payload: WireType,
)
```

### `KafkaListenerScanner`

Uses the shared `ClassGraph` scan to discover:

- Methods annotated with `org.springframework.kafka.annotation.KafkaListener`.
- Classes annotated with `@KafkaListener` whose methods are annotated with `@KafkaHandler` (multi-payload dispatch pattern). Each `@KafkaHandler` method yields its own listener site.

Returns `List<KafkaListenerSite>` where each site is `(ownerClass, method, payloadParam)`. The chosen `payloadParam` is decided by the rules in *Payload extraction — consumer* below; the scanner is responsible for that selection.

Class-name lookups are string-based; if `org.springframework.kafka.*` is absent from the user's compiled classpath, the scanner returns an empty list with no error.

### `KafkaProducerScanner`

Finds classes that have a field or constructor parameter typed `org.springframework.kafka.core.KafkaTemplate`. For each such class, reads the field's `Signature` attribute (via ASM, mirroring `DslBytecodeWalker`) to recover the concrete value type `V` from `KafkaTemplate<K, V>`.

- If `V` is `java.lang.Object`, a wildcard, a raw type, or otherwise unresolvable → the class is dropped and a `warn` message is written to `ExtractLog` (e.g. `"kafka.producer: skipped <FQCN>: KafkaTemplate value type unresolved"`). (Reasoning: without `V` there is nothing to put in the Channel's payload reference.)
- If a class holds multiple `KafkaTemplate` fields with different `V` types → each field is tracked independently and matched at the call site by `GETFIELD` owner+name+descriptor.

### `KafkaProducerBytecodeWalker`

Modeled on `DslBytecodeWalker`. For each class surviving the scanner step, walks each method's instructions and records every `INVOKEVIRTUAL` whose owner is `org/springframework/kafka/core/KafkaTemplate` and whose name is `send`. The preceding `GETFIELD` (or `ALOAD` for constructor-injected templates) identifies which `KafkaTemplate` field is in use, and therefore which `V`.

Output: `List<KafkaProducerSite>` of `(ownerClass, enclosingMethod, valueType)`. Multiple `send` calls in one method collapse into one site (deduplicated on `(ownerClass, enclosingMethod, valueType)`); calls in different methods yield separate sites.

`send` overloads currently supported (descriptor-matched):

- `send(String, Object)` → `CompletableFuture`
- `send(String, Object, Object)`
- `send(String, Integer, Object, Object)`
- `send(String, Integer, Long, Object, Object)`

Overloads taking `ProducerRecord` or `Message` are recognized but skipped with an `ExtractLog.warn` message identifying the call site.

### `KafkaChannelExtractor`

Turns a `KafkaListenerSite` or `KafkaProducerSite` into a `model.Channel`. Resolves the JVM type through the existing `TypeExtractor` (so DTO/enum/refined handling, Jackson naming, validation constraints, and nullability all reuse current behavior). Constructs `Channel(ownerSimpleName, name = PascalCase(methodName), payload = wireType)`.

### `WirespecAstBuilder.toChannel`

```kotlin
fun toChannel(c: model.Channel): WsChannel = WsChannel(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(c.name),
    reference = toReference(c.payload),
)
```

Slots into the existing definitions map. `Emitter.write` is unchanged.

## Payload extraction — consumer

Applied in order on the listener method's parameter list. The first rule that yields a payload type wins.

1. Parameter annotated `@org.springframework.messaging.handler.annotation.Payload` → use that parameter's type.
2. Exactly one "non-meta" parameter, where meta = `org.springframework.kafka.support.Acknowledgment`, `org.apache.kafka.clients.consumer.Consumer`, or any parameter annotated `@Header` / `@Headers` → use that parameter's type.
3. Otherwise: skip the listener with an `ExtractLog.warn` message (e.g. `"kafka.consumer: skipped <FQCN>.<method>: ambiguous payload parameter"`).

Wrapper unwrapping is applied to the selected parameter's type:

- `org.apache.kafka.clients.consumer.ConsumerRecord<K, V>` → `V`
- `org.springframework.messaging.Message<T>` → `T`
- `java.util.List<T>` → `T` (batch listener — value type only; iterable shape dropped, matching the chosen "value type only" payload rule)

Multi-topic `@KafkaListener(topics = {"a", "b"})` produces a single channel; topic strings are not read.

If unwrapping reaches a wildcard or raw type, the listener is skipped with an `ExtractLog.warn` message.

## Payload extraction — producer

1. Recover `V` from the originating `KafkaTemplate<K, V>` field's generic signature.
2. Channel name = `PascalCase(enclosingMethodName)`.
3. Multiple `send` calls in one method → one channel; multiple methods → multiple channels; `V` differences across sites in the same method (only possible if a method uses two differently-typed `KafkaTemplate` fields) → emit one channel per distinct `V`, name-disambiguated as `<MethodName>_<TypeSimpleName>`. (Edge case; expected to be rare.)

## Naming and file grouping

- Channel `identifier` = `PascalCase(methodName)`. Example: `onOrderCreated` → `OnOrderCreated`, `publishOrder` → `PublishOrder`.
- File grouping reuses the existing `Map<String, List<Definition>>` keyed by owning class simple name: `OrderConsumer.kt` → `OrderConsumer.ws`, `OrderPublisher.kt` → `OrderPublisher.ws`.
- Payload DTOs follow `TypeOwnership` unchanged: single-owner DTOs inline into the owner file; DTOs used by more than one owner file float to `types.ws`. HTTP endpoints and Kafka channels can therefore share `OrderEvent` via `types.ws` without special-casing.

If a Kafka listener method's `PascalCase(name)` collides with an existing HTTP endpoint definition in the same owner file, the second registration loses and an `ExtractLog.warn` message is emitted naming the collision. (Expected to be vanishingly rare in practice — Spring controllers and Kafka consumers are normally in different classes.)

## Error handling and classpath gating

- `extractor-core` does **not** add `org.springframework.kafka:*` as a runtime dependency. Both scanners use string-based class-name lookups via `ClassGraph` / ASM, so they cleanly no-op when the user's project does not depend on Spring Kafka. This mirrors how `DslRouteScanner` handles WebFlux: optional from the extractor's perspective.
- Every unresolvable case (no payload, ambiguous parameter list, raw `KafkaTemplate`, missing `Signature` attribute, unsupported `send` overload) produces an `ExtractLog.warn` message prefixed with a `kafka.consumer:` or `kafka.producer:` tag and is otherwise silent. The extractor never throws on Kafka-shaped input.

## Testing

### Unit tests (`extractor-core/src/test/kotlin/...`)

- `KafkaListenerScannerTest` — discovers method-level `@KafkaListener`, discovers class-level `@KafkaListener` + `@KafkaHandler` methods, ignores classes without either.
- `KafkaListenerPayloadRulesTest` — one case per payload rule: `@Payload`, single non-meta parameter, `ConsumerRecord` unwrap, `Message` unwrap, `List` batch unwrap, ambiguous parameter list (skipped), raw `KafkaTemplate` (skipped).
- `KafkaProducerScannerTest` — recovers `V` from field generic signature; raw `KafkaTemplate` is dropped; two fields with different `V` types are tracked independently.
- `KafkaProducerBytecodeWalkerTest` — synthetic class with two `send` call sites in different methods produces two sites; two sites in one method collapse to one; an unsupported `ProducerRecord` overload produces a skip entry.
- `WirespecAstBuilderChannelTest` — `model.Channel` maps to `WsChannel` with the expected identifier and reference; primitive, custom, list, and dict payload references all round-trip.

### Integration tests

Add `integration-tests-gradle/src/it/kafka-app/` and `integration-tests-maven/src/it/kafka-app/` fixtures. Each contains a Spring Boot Kotlin app with:

- `OrderEvent`, `ShipmentEvent` DTOs shared across consumer and producer (to exercise `types.ws` floating).
- `OrderConsumer` with five `@KafkaListener` methods covering: plain payload, `ConsumerRecord`, `Message`, `List` batch, `@Payload` + `@Header`.
- `ShipmentRouter` with class-level `@KafkaListener` + two `@KafkaHandler` methods on different payload types.
- `OrderPublisher` injecting `KafkaTemplate<String, OrderEvent>` and `KafkaTemplate<String, ShipmentEvent>`, with two publish methods.

Verifiers (`DslFixtureVerifiers`-style) assert:

- `OrderConsumer.ws` contains the expected five `channel` definitions with the right payload references.
- `ShipmentRouter.ws` contains one channel per `@KafkaHandler`.
- `OrderPublisher.ws` contains two channels, one per publish method.
- `OrderEvent` ends up in `types.ws` (shared) while a consumer-only DTO inlines into its owner file.
- A "no Spring Kafka on classpath" fixture (existing `basic-kotlin-app` already qualifies) still emits no Kafka content and is unaffected.

## Implementation order

Suggested ordering for the plan that follows this spec:

1. `model.Channel` + `WirespecAstBuilder.toChannel` + a trivial emit test (proves the AST round-trip before any scanning code exists).
2. `KafkaListenerScanner` + payload rules + unit tests.
3. `KafkaChannelExtractor` wiring + `WirespecExtractor` integration + a minimal integration test fixture with one method-level consumer.
4. `@KafkaHandler` class-level support.
5. `KafkaProducerScanner` + `KafkaProducerBytecodeWalker` + producer integration tests.
6. Expand the integration fixtures to the full matrix described above.

Each step is independently testable and leaves the HTTP pipeline untouched.
