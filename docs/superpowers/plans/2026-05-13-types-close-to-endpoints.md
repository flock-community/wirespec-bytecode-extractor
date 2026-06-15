# Types close to endpoints — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make controller `.ws` files include the DTO/enum/refined types referenced only by that controller, keeping `types.ws` for types referenced by 2+ controllers.

**Architecture:** Add a pure post-processing analyzer (`TypeOwnership`) that runs after `EndpointExtractor`/`TypeExtractor` and before `Emitter.write`. It walks each controller's endpoint AST to compute the transitive closure of `Reference.Custom` names, inverts to `typeName → Set<controller>`, and partitions the full type list into per-controller buckets (size 1) and a shared bucket (size ≥ 2). `Emitter.write` is unchanged in semantics; its first map parameter is renamed for clarity.

**Tech Stack:** Kotlin 2.x, JUnit 5, Kotest assertions (`io.kotest:kotest-assertions-core`), Wirespec compiler AST (`community.flock.wirespec.compiler:core-jvm:0.17.20`), Gradle.

**Spec:** [`docs/superpowers/specs/2026-05-13-types-close-to-endpoints-design.md`](../specs/2026-05-13-types-close-to-endpoints-design.md).

---

## File map

**Create**

- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnership.kt`
- `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt`
- `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`

**Modify**

- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt` (rename parameter)
- `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt` (rename usages)
- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt` (wire `TypeOwnership.partition`)
- `integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt`
- `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`
- `README.md` (Output layout section)

---

## Task 1: Reference name extraction helpers

Pure functions that yield the `Reference.Custom` names reachable from a given AST node. Internal to `TypeOwnership.kt`; we test them through the `partition` API, so this task creates the file scaffolding plus the helpers without yet exposing `partition`.

**Files:**

- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnership.kt`
- Test: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt`

- [ ] **Step 1: Write a failing test for the partition API surface (compile-only smoke test)**

Create `TypeOwnershipTest.kt`:

```kotlin
package community.flock.wirespec.extractor.ownership

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TypeOwnershipTest {

    @Test
    fun `empty input yields empty partition`() {
        val result = TypeOwnership.partition(
            endpointsByController = emptyMap(),
            allTypes = emptyList(),
        )
        result.perController shouldBe emptyMap()
        result.shared shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run it to verify it fails (unresolved reference)**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest' -i`
Expected: compilation fails — `Unresolved reference: TypeOwnership` (or similar).

- [ ] **Step 3: Create `TypeOwnership.kt` with the helpers and a stub `partition`**

```kotlin
package community.flock.wirespec.extractor.ownership

import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType

/**
 * Partitions extracted Wirespec definitions into "lives with one controller"
 * vs. "shared by 2+ controllers".
 *
 * A type "belongs" to a controller if it is reachable from any of that
 * controller's endpoint references (path params, queries, headers, request
 * content, response content), transitively through Object/Type field
 * references. Enums and Refined definitions are leaves in the reference graph.
 *
 * Definitions reachable from exactly one controller are appended to that
 * controller's existing endpoint list. Definitions reachable from two or more
 * controllers (or — defensively — none) end up in `shared`.
 */
internal object TypeOwnership {

    data class Partition(
        /** controllerName -> endpoint definitions (input) + owned type definitions (appended). */
        val perController: Map<String, List<Definition>>,
        /** Type definitions referenced by 2+ controllers (or zero). */
        val shared: List<Definition>,
    )

    fun partition(
        endpointsByController: Map<String, List<Definition>>,
        allTypes: List<Definition>,
        onWarn: (String) -> Unit = {},
    ): Partition {
        // Stub — Task 2 fills this in.
        return Partition(perController = endpointsByController, shared = allTypes)
    }

    /** Names of every `Reference.Custom` reachable from [reference]. */
    internal fun customNamesIn(reference: Reference): Sequence<String> = when (reference) {
        is Reference.Custom    -> sequenceOf(reference.value)
        is Reference.Iterable  -> customNamesIn(reference.reference)
        is Reference.Dict      -> customNamesIn(reference.reference)
        is Reference.Primitive -> emptySequence()
        is Reference.Any       -> emptySequence()
        is Reference.Unit      -> emptySequence()
    }

    /** Names of every `Reference.Custom` reachable from an endpoint's signature. */
    internal fun customNamesIn(endpoint: WsEndpoint): Set<String> {
        val out = linkedSetOf<String>()
        endpoint.path.forEach { seg ->
            if (seg is WsEndpoint.Segment.Param) out += customNamesIn(seg.reference).toList()
        }
        endpoint.queries.forEach  { f -> out += customNamesIn(f.reference).toList() }
        endpoint.headers.forEach  { f -> out += customNamesIn(f.reference).toList() }
        endpoint.requests.forEach { r -> r.content?.reference?.let { out += customNamesIn(it).toList() } }
        endpoint.responses.forEach { r ->
            r.content?.reference?.let { out += customNamesIn(it).toList() }
            r.headers.forEach { h -> out += customNamesIn(h.reference).toList() }
        }
        return out
    }

    /** Names of every `Reference.Custom` reachable from a Type's shape fields and extends list. */
    internal fun customNamesIn(type: WsType): Set<String> {
        val out = linkedSetOf<String>()
        type.shape.value.forEach { f -> out += customNamesIn(f.reference).toList() }
        type.extends.forEach { ref -> out += customNamesIn(ref).toList() }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest' -i`
Expected: `empty input yields empty partition` PASSES (the stub returns the inputs unchanged, which matches empty/empty).

- [ ] **Step 5: Add unit tests covering the helpers via `partition` semantics that the helpers enable (still using the stub) — and verify the stub is wrong**

Append to `TypeOwnershipTest.kt`:

```kotlin
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Field as WsField
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum

private fun endpoint(name: String, responseRef: Reference?): WsEndpoint = WsEndpoint(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    method = WsEndpoint.Method.GET,
    path = listOf(WsEndpoint.Segment.Literal("p")),
    queries = emptyList(),
    headers = emptyList(),
    requests = listOf(WsEndpoint.Request(content = null)),
    responses = listOf(WsEndpoint.Response(
        status = "200",
        headers = emptyList(),
        content = responseRef?.let { WsEndpoint.Content("application/json", it) },
        annotations = emptyList(),
    )),
)

private fun typeDef(name: String, vararg fieldNameToRefName: Pair<String, String>): WsType = WsType(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    shape = WsType.Shape(
        value = fieldNameToRefName.map { (fn, refName) ->
            WsField(
                annotations = emptyList(),
                identifier = FieldIdentifier(fn),
                reference = Reference.Custom(refName, isNullable = false),
            )
        }
    ),
    extends = emptyList(),
)

private fun enumDef(name: String): WsEnum = WsEnum(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    entries = setOf("A", "B"),
)

@Test
fun `single controller owning one type — type moves into controller`() {
    val ep = endpoint("GetUser", Reference.Custom("UserDto", false))
    val userDto = typeDef("UserDto")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("UserController" to listOf(ep)),
        allTypes = listOf(userDto),
    )

    result.perController["UserController"] shouldBe listOf(ep, userDto)
    result.shared shouldBe emptyList()
}
```

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest' -i`
Expected: the new test FAILS — the stub still returns `allTypes` as `shared`, so `result.shared` is `[UserDto]` and `result.perController["UserController"]` is `[ep]`.

- [ ] **Step 6: Commit (red tests + helpers in place; partition logic still stubbed)**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnership.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt
git commit -m "feat(ownership): scaffold TypeOwnership with reference-walk helpers"
```

---

## Task 2: Implement `TypeOwnership.partition`

Replace the stub with the real partition logic and drive it red-green with the full test matrix from the spec.

**Files:**

- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnership.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt`

- [ ] **Step 1: Implement the real `partition`**

Replace the stub body inside `TypeOwnership.partition`:

```kotlin
fun partition(
    endpointsByController: Map<String, List<Definition>>,
    allTypes: List<Definition>,
    onWarn: (String) -> Unit = {},
): Partition {
    // 1. Index allTypes by their identifier value.
    val typeByName: Map<String, Definition> = allTypes.associateBy { it.identifier.value }

    // 2. Compute the transitive closure of Custom references per controller.
    val reachable: Map<String, Set<String>> = endpointsByController.mapValues { (_, defs) ->
        val visited = linkedSetOf<String>()
        val frontier = ArrayDeque<String>()
        defs.filterIsInstance<WsEndpoint>().forEach { ep ->
            customNamesIn(ep).forEach { name ->
                if (visited.add(name)) frontier += name
            }
        }
        while (frontier.isNotEmpty()) {
            val name = frontier.removeFirst()
            val def = typeByName[name] ?: continue
            val children = when (def) {
                is WsType -> customNamesIn(def)
                else      -> emptySet()  // Enum / Refined / etc. are leaves
            }
            for (child in children) if (visited.add(child)) frontier += child
        }
        visited
    }

    // 3. Invert: typeName -> set of controllers that reach it.
    val ownersByType = mutableMapOf<String, MutableSet<String>>()
    for ((controller, names) in reachable) {
        for (n in names) ownersByType.getOrPut(n) { mutableSetOf() } += controller
    }

    // 4. Partition allTypes, preserving registration order.
    val perControllerExtras = linkedMapOf<String, MutableList<Definition>>()
    val shared = mutableListOf<Definition>()
    for (def in allTypes) {
        val name = def.identifier.value
        val owners = ownersByType[name] ?: emptySet()
        when {
            owners.size == 1 -> perControllerExtras.getOrPut(owners.first()) { mutableListOf() } += def
            owners.size >= 2 -> shared += def
            else -> {
                onWarn("Type $name has no owning controller; placing in types.ws")
                shared += def
            }
        }
    }

    // 5. Compose: endpoints first, owned types appended.
    val merged = linkedMapOf<String, List<Definition>>()
    for ((controller, endpoints) in endpointsByController) {
        merged[controller] = endpoints + (perControllerExtras[controller].orEmpty())
    }

    return Partition(perController = merged, shared = shared)
}
```

- [ ] **Step 2: Run the existing failing test, verify it now passes**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest.single controller owning one type — type moves into controller' -i`
Expected: PASS.

- [ ] **Step 3: Add remaining cases — write all red, run, then they should pass since the logic is in place**

Append to `TypeOwnershipTest.kt`:

```kotlin
@Test
fun `two controllers sharing a type — type goes to shared`() {
    val epA = endpoint("GetUserA", Reference.Custom("UserDto", false))
    val epB = endpoint("GetUserB", Reference.Custom("UserDto", false))
    val userDto = typeDef("UserDto")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
        allTypes = listOf(userDto),
    )

    result.perController["A"] shouldBe listOf(epA)
    result.perController["B"] shouldBe listOf(epB)
    result.shared shouldBe listOf(userDto)
}

@Test
fun `two controllers with disjoint types — each keeps its own`() {
    val epA = endpoint("GetFoo", Reference.Custom("Foo", false))
    val epB = endpoint("GetBar", Reference.Custom("Bar", false))
    val foo = typeDef("Foo")
    val bar = typeDef("Bar")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
        allTypes = listOf(foo, bar),
    )

    result.perController["A"] shouldBe listOf(epA, foo)
    result.perController["B"] shouldBe listOf(epB, bar)
    result.shared shouldBe emptyList()
}

@Test
fun `transitive ownership — nested type follows its parent into the controller`() {
    val ep = endpoint("GetUser", Reference.Custom("UserDto", false))
    val userDto = typeDef("UserDto", "address" to "Address")
    val address = typeDef("Address", "street" to "String")  // String not in typeByName → treated as unresolved leaf

    // Note: in the real extractor, "String" would be a Reference.Primitive, not Custom.
    // Here we keep "Address.street" as a Custom("String") so we exercise the unresolved-name branch.
    val result = TypeOwnership.partition(
        endpointsByController = mapOf("UserController" to listOf(ep)),
        allTypes = listOf(userDto, address),
    )

    result.perController["UserController"] shouldBe listOf(ep, userDto, address)
    result.shared shouldBe emptyList()
}

@Test
fun `transitive promotion — nested type referenced directly by another controller moves to shared`() {
    val epA = endpoint("GetUser", Reference.Custom("UserDto", false))
    val epB = endpoint("GetAddress", Reference.Custom("Address", false))
    val userDto = typeDef("UserDto", "address" to "Address")
    val address = typeDef("Address")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
        allTypes = listOf(userDto, address),
    )

    result.perController["A"] shouldBe listOf(epA, userDto)
    result.perController["B"] shouldBe listOf(epB)
    result.shared shouldBe listOf(address)
}

@Test
fun `enum shared across controllers goes to shared`() {
    val epA = endpoint("GetA", Reference.Custom("Status", false))
    val epB = endpoint("GetB", Reference.Custom("Status", false))
    val status = enumDef("Status")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
        allTypes = listOf(status),
    )

    result.shared shouldBe listOf(status)
    result.perController["A"] shouldBe listOf(epA)
    result.perController["B"] shouldBe listOf(epB)
}

@Test
fun `cyclic references do not loop and ownership is single-controller`() {
    val ep = endpoint("GetNode", Reference.Custom("Node", false))
    val node = typeDef("Node", "next" to "Node")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("Tree" to listOf(ep)),
        allTypes = listOf(node),
    )

    result.perController["Tree"] shouldBe listOf(ep, node)
    result.shared shouldBe emptyList()
}

@Test
fun `orphan type with no controller reference goes to shared and warns`() {
    val ep = endpoint("Get", Reference.Custom("UserDto", false))
    val userDto = typeDef("UserDto")
    val orphan = typeDef("Orphan")
    val warnings = mutableListOf<String>()

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("A" to listOf(ep)),
        allTypes = listOf(userDto, orphan),
        onWarn = { warnings += it },
    )

    result.perController["A"] shouldBe listOf(ep, userDto)
    result.shared shouldBe listOf(orphan)
    warnings.any { it.contains("Orphan") } shouldBe true
}

@Test
fun `refined type with single owner is placed in that controller`() {
    val ep = endpoint("GetUser", Reference.Custom("UserDto", false))
    val userDto = typeDef("UserDto", "email" to "EmailString")
    val emailString = community.flock.wirespec.compiler.core.parse.ast.Refined(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier("EmailString"),
        reference = Reference.Primitive(
            type = Reference.Primitive.Type.String(constraint = null),
            isNullable = false,
        ),
    )

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("UserController" to listOf(ep)),
        allTypes = listOf(userDto, emailString),
    )

    result.perController["UserController"] shouldBe listOf(ep, userDto, emailString)
    result.shared shouldBe emptyList()
}

@Test
fun `controller with no endpoints contributes nothing and owns nothing`() {
    val ep = endpoint("Get", Reference.Custom("UserDto", false))
    val userDto = typeDef("UserDto")

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("Empty" to emptyList(), "A" to listOf(ep)),
        allTypes = listOf(userDto),
    )

    result.perController["Empty"] shouldBe emptyList()
    result.perController["A"] shouldBe listOf(ep, userDto)
    result.shared shouldBe emptyList()
}

@Test
fun `owned types are appended in registration order, not in reference order`() {
    val ep = endpoint("Get", Reference.Custom("B", false))  // direct ref to B first
    val a = typeDef("A")
    val b = typeDef("B", "a" to "A")  // B references A (so A is discovered after B)

    val result = TypeOwnership.partition(
        endpointsByController = mapOf("A" to listOf(ep)),
        allTypes = listOf(a, b),  // registration order: A then B
    )

    // The output preserves allTypes order: A before B.
    result.perController["A"] shouldBe listOf(ep, a, b)
}
```

- [ ] **Step 4: Run all `TypeOwnershipTest` tests, verify all pass**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest' -i`
Expected: all 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnership.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt
git commit -m "feat(ownership): partition definitions into per-controller and shared buckets"
```

---

## Task 3: Rename `Emitter.write` parameter for clarity

`controllerEndpoints` now carries endpoints **and** owned types, so the name is misleading. Rename to `controllerDefinitions`. Same semantics.

**Files:**

- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt`

- [ ] **Step 1: Update the signature and the one local reference**

In `Emitter.kt`, replace:

```kotlin
fun write(
    outputDir: File,
    controllerEndpoints: Map<String, List<Definition>>,
    sharedTypes: List<Definition>,
): List<File> {
    outputDir.mkdirs()
    clearExistingWs(outputDir)

    val written = mutableListOf<File>()

    controllerEndpoints.forEach { (controller, defs) ->
```

with:

```kotlin
fun write(
    outputDir: File,
    controllerDefinitions: Map<String, List<Definition>>,
    sharedTypes: List<Definition>,
): List<File> {
    outputDir.mkdirs()
    clearExistingWs(outputDir)

    val written = mutableListOf<File>()

    controllerDefinitions.forEach { (controller, defs) ->
```

Also update the KDoc bullet:

```
 *  - Writes one `<ControllerName>.ws` per entry of [controllerEndpoints].
```

becomes:

```
 *  - Writes one `<ControllerName>.ws` per entry of [controllerDefinitions]
 *    (each entry's `List<Definition>` may contain endpoints **and** owned types).
```

- [ ] **Step 2: Update `EmitterTest.kt` call sites**

In `EmitterTest.kt`, replace every `controllerEndpoints = ` with `controllerDefinitions = `. There are three occurrences (lines ~44, ~144 in the original file). The unnamed positional call at line 107 (`emitter.write(dir.toFile(), mapOf(...), emptyList())`) does not need changing, but for consistency convert it to a named call:

```kotlin
emitter.write(
    outputDir = dir.toFile(),
    controllerDefinitions = mapOf("ParamCtl" to listOf(ep)),
    sharedTypes = emptyList(),
)
```

The two positional calls inside `deletes existing ws files but leaves other files alone` (line ~119) and `writes one ws file per controller and a shared types ws` already use named args — just change the keyword.

- [ ] **Step 3: Build and run EmitterTest**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.emit.EmitterTest' -i`
Expected: all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt
git commit -m "refactor(emitter): rename controllerEndpoints to controllerDefinitions"
```

---

## Task 4: Wire `TypeOwnership` into `WirespecExtractor`

Call `TypeOwnership.partition` between extraction and emission. Existing unit tests (`TypeOwnershipTest`) and the upcoming integration test updates (Tasks 5, 6) cover the behaviour change end-to-end — no new test in this task.

**Files:**

- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt`

- [ ] **Step 1: Add the import**

At the top of `WirespecExtractor.kt`, alongside the existing imports, add:

```kotlin
import community.flock.wirespec.extractor.ownership.TypeOwnership
```

- [ ] **Step 2: Replace the extraction-to-emission block**

Locate this block inside `WirespecExtractor.extract`:

```kotlin
val byController = controllers.associate { c ->
    val eps = try {
        endpoints.extract(c).map(builder::toEndpoint)
    } catch (t: Throwable) {
        config.log.warn("Skipping ${c.name}: ${t.message}")
        emptyList()
    }
    c.simpleName to eps.map { it as Definition }
}.filterValues { it.isNotEmpty() }

val sharedTypes = types.definitions.mapNotNull { def ->
    try {
        builder.toDefinition(def)
    } catch (t: Throwable) {
        config.log.warn("Skipping type ${def}: ${t.message}")
        null
    }
}

val filesWritten = Emitter().write(
    outputDir = config.outputDirectory,
    controllerEndpoints = byController,
    sharedTypes = sharedTypes,
)
config.log.info(
    "Wrote ${byController.size + (if (sharedTypes.isEmpty()) 0 else 1)} .ws file(s) to ${config.outputDirectory.absolutePath}"
)

ExtractResult(
    controllerCount = byController.size,
    sharedTypeCount = sharedTypes.size,
    filesWritten = filesWritten,
)
```

Replace it with:

```kotlin
val byController = controllers.associate { c ->
    val eps = try {
        endpoints.extract(c).map(builder::toEndpoint)
    } catch (t: Throwable) {
        config.log.warn("Skipping ${c.name}: ${t.message}")
        emptyList()
    }
    c.simpleName to eps.map { it as Definition }
}.filterValues { it.isNotEmpty() }

val allTypes = types.definitions.mapNotNull { def ->
    try {
        builder.toDefinition(def)
    } catch (t: Throwable) {
        config.log.warn("Skipping type ${def}: ${t.message}")
        null
    }
}

val partition = TypeOwnership.partition(
    endpointsByController = byController,
    allTypes = allTypes,
    onWarn = { msg -> config.log.warn(msg) },
)

val filesWritten = Emitter().write(
    outputDir = config.outputDirectory,
    controllerDefinitions = partition.perController,
    sharedTypes = partition.shared,
)
config.log.info(
    "Wrote ${partition.perController.size + (if (partition.shared.isEmpty()) 0 else 1)} .ws file(s) to ${config.outputDirectory.absolutePath}"
)

ExtractResult(
    controllerCount = partition.perController.size,
    sharedTypeCount = partition.shared.size,
    filesWritten = filesWritten,
)
```

- [ ] **Step 3: Run the full extractor-core test suite**

Run: `./gradlew :extractor-core:test`
Expected: BUILD SUCCESSFUL with all tests passing — `TypeOwnershipTest` (Task 2), `EmitterTest` (Task 3), `WirespecExtractorTest`, and every other existing test.

If `WirespecExtractorTest.extract writes ws files for known controllers and returns counts` fails because the file count changed, debug it: that test asserts `result.controllerCount == files.minus("types.ws").size`. Since `result.controllerCount` is `partition.perController.size` and we filter `types.ws` from `files`, the relation still holds. If it fails anyway, add a temporary `println(out.listFiles()!!.map { it.name })` to inspect.

- [ ] **Step 4: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt
git commit -m "feat(extractor): place types close to their controllers via TypeOwnership"
```

---

## Task 5: Maven integration tests — add second controller and update assertions

Add a second controller in both Maven fixtures that references `Role` directly. After the change, `UserDto` (with its many fields) lives in `UserController.ws`; `Role` is shared by both controllers and stays in `types.ws`.

**Files:**

- Create: `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- Create: `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`
- Modify: `integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt`

- [ ] **Step 1: Add the Kotlin fixture's `AdminController.kt`**

Create `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`:

```kotlin
package com.acme.api

import com.acme.api.dto.Role
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Second controller in the fixture so the integration test verifies that
 * `Role` (referenced by both AdminController and — transitively via UserDto —
 * UserController) lands in `types.ws`, while `UserDto` (referenced only by
 * UserController) moves into `UserController.ws`.
 */
@RestController
@RequestMapping("/admins")
class AdminController {

    @GetMapping("/by-role/{role}")
    fun listByRole(@PathVariable role: Role): List<String> = throw NotImplementedError()
}
```

- [ ] **Step 2: Add the Java fixture's `AdminController.java`**

Create `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`:

```java
package com.acme.api;

import com.acme.api.dto.Role;
import java.util.Collections;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * Second controller in the fixture so the integration test verifies that
 * `Role` (referenced by both AdminController and — transitively via UserDto —
 * UserController) lands in `types.ws`, while `UserDto` (referenced only by
 * UserController) moves into `UserController.ws`.
 */
@RestController
@RequestMapping("/admins")
public class AdminController {

    @GetMapping("/by-role/{role}")
    public List<String> listByRole(@PathVariable Role role) {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 3: Update the Maven IT verifier — `verifyBasicKotlinApp`**

In `FixtureBuildTest.kt`, replace the body of `verifyBasicKotlinApp` with:

```kotlin
private fun verifyBasicKotlinApp(workDir: File) {
    val wsDir = File(workDir, "target/wirespec")
    assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

    val files = wsDir.listFiles()!!.map { it.name }.sorted()
    files.shouldContainExactly("AdminController.ws", "UserController.ws", "types.ws")

    val controller = File(wsDir, "UserController.ws").readText()
    controller shouldContain "endpoint GetUser GET /users/{id"
    controller shouldContain "endpoint CreateUser POST"
    controller shouldMatch Regex("(?s).*endpoint ListUsers GET /users\\b.*")
    controller shouldMatch Regex("(?s).*200 -> UserDto\\[].*")
    controller shouldMatch Regex("(?s).*endpoint DeleteUser DELETE /users/\\{id.*")
    controller shouldMatch Regex("(?s).*204 -> Unit.*")

    // UserDto is referenced only by UserController, so its definition lives
    // inside UserController.ws (NOT types.ws).
    controller shouldContain "type UserDto"

    // Kotlin nullability path on UserDto fields.
    controller shouldMatch Regex("(?s).*id\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*nickname\\s*:\\s*String\\?.*")

    // JDK value types filtered to String inside UserDto's fields.
    controller shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*balance\\s*:\\s*String\\b(?!\\?).*")

    // Underscored / capitalised field names backticked inside UserDto.
    controller shouldContain "`_internalId`"
    controller shouldContain "`SystemKey`"
    assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(controller)) {
        "_internalId appears un-backticked at line start in UserController.ws:\n$controller"
    }

    // AdminController has just the one endpoint.
    val admin = File(wsDir, "AdminController.ws").readText()
    admin shouldContain "endpoint ListByRole GET /admins/by-role/"

    // types.ws now holds only types referenced by ≥2 controllers. Role
    // qualifies: AdminController references it directly, UserController
    // transitively via UserDto.
    val types = File(wsDir, "types.ws").readText()
    types shouldContain "Role"
    // UserDto MUST NOT be in types.ws (it has exactly one controller owner).
    assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(types)) {
        "UserDto leaked into types.ws despite having a single owner:\n$types"
    }

    // JDK class names must not leak as Wirespec definitions in either file.
    val combined = controller + "\n" + types + "\n" + admin
    listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
        assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(combined)) {
            "JDK type $jdk leaked into a .ws file:\n$combined"
        }
    }

    // Kotlin coroutine machinery must never appear as Wirespec types.
    listOf("Continuation", "CoroutineContext").forEach { ktInternal ->
        assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$ktInternal\\b").containsMatchIn(combined)) {
            "Kotlin coroutine type $ktInternal leaked into a .ws file:\n$combined"
        }
    }
}
```

- [ ] **Step 4: Update the Maven IT verifier — `verifyBasicSpringApp`**

Replace its body with:

```kotlin
private fun verifyBasicSpringApp(workDir: File) {
    val wsDir = File(workDir, "target/wirespec")
    assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

    val files = wsDir.listFiles()!!.map { it.name }.sorted()
    files.shouldContainExactly("AdminController.ws", "UserController.ws", "types.ws")

    val controller = File(wsDir, "UserController.ws").readText()
    controller shouldContain "endpoint GetUser GET /users/{id"
    controller shouldContain "endpoint CreateUser POST"
    controller shouldContain "type UserDto"

    // JDK value types filtered to String on UserDto fields. Java records are
    // all-nullable, so accept both `String` and `String?` forms.
    controller shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\??.*")
    controller shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\??.*")
    controller shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\??.*")
    controller shouldMatch Regex("(?s).*balance\\s*:\\s*String\\??.*")

    controller shouldContain "`_internalId`"
    controller shouldContain "`SystemKey`"
    assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(controller)) {
        "_internalId appears un-backticked at line start in UserController.ws:\n$controller"
    }

    val admin = File(wsDir, "AdminController.ws").readText()
    admin shouldContain "endpoint ListByRole GET /admins/by-role/"

    val types = File(wsDir, "types.ws").readText()
    types shouldContain "Role"
    assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(types)) {
        "UserDto leaked into types.ws despite having a single owner:\n$types"
    }

    val combined = controller + "\n" + types + "\n" + admin
    listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
        assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(combined)) {
            "JDK type $jdk leaked into a .ws file:\n$combined"
        }
    }
}
```

- [ ] **Step 5: Run the Maven integration tests**

Run: `./gradlew :integration-tests-maven:test`
Expected: BUILD SUCCESSFUL, both `basic-kotlin-app` and `basic-spring-app` dynamic tests pass. If anything fails, read the failure message — the verifier prints the offending file contents in its assertion messages.

- [ ] **Step 6: Commit**

```bash
git add integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt \
        integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java \
        integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt
git commit -m "test(it-maven): add AdminController to verify shared-vs-owned type placement"
```

---

## Task 6: Gradle integration tests — same as Task 5 for the Gradle fixtures

**Files:**

- Create: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- Create: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`

- [ ] **Step 1: Add the Kotlin fixture's `AdminController.kt`**

Create `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`:

```kotlin
package com.acme.api

import com.acme.api.dto.Role
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Second controller in the fixture so the integration test verifies that
 * `Role` (referenced by both AdminController and — transitively via UserDto —
 * UserController) lands in `types.ws`, while `UserDto` (referenced only by
 * UserController) moves into `UserController.ws`.
 */
@RestController
@RequestMapping("/admins")
class AdminController {

    @GetMapping("/by-role/{role}")
    fun listByRole(@PathVariable role: Role): List<String> = throw NotImplementedError()
}
```

- [ ] **Step 2: Add the Java fixture's `AdminController.java`**

Create `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`:

```java
package com.acme.api;

import com.acme.api.dto.Role;
import java.util.Collections;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admins")
public class AdminController {

    @GetMapping("/by-role/{role}")
    public List<String> listByRole(@PathVariable Role role) {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 3: Update the Gradle IT verifier — `verifyBasicKotlinApp`**

In `GradleFixtureBuildTest.kt`, replace the body of `verifyBasicKotlinApp` with:

```kotlin
private fun verifyBasicKotlinApp(workDir: File) {
    val wsDir = File(workDir, "build/wirespec")
    assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

    val files = wsDir.listFiles()!!.map { it.name }.sorted()
    files.shouldContainExactly("AdminController.ws", "UserController.ws", "types.ws")

    val controller = File(wsDir, "UserController.ws").readText()
    controller shouldContain "endpoint GetUser GET /users/{id"
    controller shouldContain "endpoint CreateUser POST"
    controller shouldMatch Regex("(?s).*endpoint ListUsers GET /users\\b.*")
    controller shouldMatch Regex("(?s).*200 -> UserDto\\[].*")
    controller shouldMatch Regex("(?s).*endpoint DeleteUser DELETE /users/\\{id.*")
    controller shouldMatch Regex("(?s).*204 -> Unit.*")
    controller shouldContain "type UserDto"

    controller shouldMatch Regex("(?s).*id\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*nickname\\s*:\\s*String\\?.*")
    controller shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\b(?!\\?).*")
    controller shouldMatch Regex("(?s).*balance\\s*:\\s*String\\b(?!\\?).*")
    controller shouldContain "`_internalId`"
    controller shouldContain "`SystemKey`"
    assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(controller)) {
        "_internalId appears un-backticked at line start in UserController.ws:\n$controller"
    }

    val admin = File(wsDir, "AdminController.ws").readText()
    admin shouldContain "endpoint ListByRole GET /admins/by-role/"

    val types = File(wsDir, "types.ws").readText()
    types shouldContain "Role"
    assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(types)) {
        "UserDto leaked into types.ws despite having a single owner:\n$types"
    }

    val combined = controller + "\n" + types + "\n" + admin
    listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
        assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(combined)) {
            "JDK type $jdk leaked into a .ws file:\n$combined"
        }
    }
    listOf("Continuation", "CoroutineContext").forEach { ktInternal ->
        assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$ktInternal\\b").containsMatchIn(combined)) {
            "Kotlin coroutine type $ktInternal leaked into a .ws file:\n$combined"
        }
    }
}
```

- [ ] **Step 4: Update the Gradle IT verifier — `verifyBasicSpringApp`**

Replace its body with:

```kotlin
private fun verifyBasicSpringApp(workDir: File) {
    val wsDir = File(workDir, "build/wirespec")
    assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

    val files = wsDir.listFiles()!!.map { it.name }.sorted()
    files.shouldContainExactly("AdminController.ws", "UserController.ws", "types.ws")

    val controller = File(wsDir, "UserController.ws").readText()
    controller shouldContain "endpoint GetUser GET /users/{id"
    controller shouldContain "endpoint CreateUser POST"
    controller shouldContain "type UserDto"
    controller shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\??.*")
    controller shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\??.*")
    controller shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\??.*")
    controller shouldMatch Regex("(?s).*balance\\s*:\\s*String\\??.*")
    controller shouldContain "`_internalId`"
    controller shouldContain "`SystemKey`"
    assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(controller)) {
        "_internalId appears un-backticked at line start in UserController.ws:\n$controller"
    }

    val admin = File(wsDir, "AdminController.ws").readText()
    admin shouldContain "endpoint ListByRole GET /admins/by-role/"

    val types = File(wsDir, "types.ws").readText()
    types shouldContain "Role"
    assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(types)) {
        "UserDto leaked into types.ws despite having a single owner:\n$types"
    }

    val combined = controller + "\n" + types + "\n" + admin
    listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
        assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(combined)) {
            "JDK type $jdk leaked into a .ws file:\n$combined"
        }
    }
}
```

- [ ] **Step 5: Run the Gradle integration tests**

Run: `./gradlew :integration-tests-gradle:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt \
        integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java \
        integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt
git commit -m "test(it-gradle): add AdminController to verify shared-vs-owned type placement"
```

---

## Task 7: Update README

Reflect the new output layout in the user-facing docs.

**Files:**

- Modify: `README.md`

- [ ] **Step 1: Replace the "Output layout" section**

In `README.md`, find the section heading `## Output layout` and replace its bullet list (currently 3 bullets) with:

```markdown
## Output layout

- One `<ControllerName>.ws` per controller — endpoints, plus DTO/enum/refined
  types referenced only by that controller.
- One shared `types.ws` — DTO/enum/refined types referenced by two or more
  controllers. Omitted when all types are controller-local.
- The output directory is treated as a generated artifact: existing `*.ws`
  files are deleted on each run; non-`.ws` files are left alone.
```

- [ ] **Step 2: Run the full build to ensure nothing was missed**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all unit and integration tests green.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document new types-close-to-endpoints output layout"
```

---

## Final verification

After all tasks:

- [ ] `./gradlew build` — full project build is green.
- [ ] `git log --oneline -n 8` — confirms 6–7 atomic commits matching the task names.
- [ ] Visually inspect a fresh extraction output (or rerun the integration tests with `--info`) — confirm `UserDto` ends up in `UserController.ws` and `Role` in `types.ws`.
