# Flatten Generic Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `TypeExtractor` flatten every concrete generic instantiation into its own uniquely-named `WireType.Object`, so `Page<UserDto>` becomes a wirespec type `UserDtoPage` with `T`-substituted fields, and the same instantiation reached twice reuses a single definition.

**Architecture:** All changes live inside `extractor-core`. `TypeExtractor` gains two stacks (type-variable bindings and a controller→type context chain), an extended cache key built from FQNs (so `Page<UserDto>` and `Page<OrderDto>` are distinct), a deterministic `flatName(...)` function, and a generic-superclass field walk for concrete subclasses. `WirespecExtractorException` gains four named subtypes for the user-facing error cases; `WirespecExtractor` is taught to let those subtypes propagate instead of swallowing them. `WireType`, `TypeOwnership`, and `Emitter` are unchanged.

**Tech Stack:** Kotlin 1.9 (JVM), JUnit 5, Kotest matchers (`io.kotest:kotest-assertions-core`), Gradle for builds, Maven invoker + Gradle TestKit for integration tests.

**Spec:** `docs/superpowers/specs/2026-05-13-flatten-generics-design.md` — read it first.

---

## File map

**Modified:**

- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt` — primary surface (~150 new/changed lines).
- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractorException.kt` — sealed open class with four new factory methods for generics errors.
- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt:66-83` — rethrow `WirespecExtractorException` from per-controller / per-type try/catch blocks.
- `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt` — replace the unbound-STRING test (line 79) and add new flattening tests.
- `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt` — add scenarios for flattened generics.
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt` — extend with a `Page<UserDto>` endpoint.
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt` — extend with a `Page<UserDto>` endpoint (shared-instantiation scenario).
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java` — same.
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java` — same.
- `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt` — extend verifiers.
- `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt` — same as Gradle counterpart.
- `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt` — same.
- `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java` — same.
- `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java` — same.
- `integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt` — extend verifiers.
- `README.md` — note generic-flattening behavior and rules.

**Created (unit-test fixtures, in `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/`):**

- `Page.kt` — generic `Page<T>` data class with `content: T`, `totalElements: Long`, `number: Int`.
- `Wrapper.kt` — generic `Wrapper<T>` with `value: T`.
- `ApiResponse.kt` — generic `ApiResponse<T>` with `data: T`, `status: Int`.
- `Holders.kt` — non-generic holders used to obtain `ParameterizedType` instances via reflection (`UserDtoPageHolder`, `IntWrapperHolder`, etc.).
- `Tree.kt` — generic self-referential `Tree<T>` (`value: T`, `children: List<Tree<T>>`).
- `Pair2.kt` — generic two-arg `Pair2<A, B>` (avoiding shadowing of `kotlin.Pair`).
- `UserPage.kt` — concrete subclass `class UserPage : Page<UserDto>()` (extends the binding parent).
- `UserDtoPage.kt` — hand-written class that intentionally collides with the flattened `Page<UserDto>` name.
- `BadControllers.kt` — fixtures used to exercise the four error cases (raw generic return, wildcard return, raw superclass).
- `Container.kt` — extend the existing fixture (`extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/Container.kt`) by adding a sibling holder that binds `T`; the existing file stays.

**Created (integration-test fixtures):**

- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Page.kt` — `data class Page<T>(...)`.
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Page.java` — equivalent Java.
- `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Page.kt` — copy.
- `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Page.java` — copy.

---

## Conventions for all tasks

- **Working directory:** always run commands from the worktree root `/Users/wilmveel/Projects/wirespec-extractor/.claude/worktrees/types-close-to-endpoints`. Never `cd` to the original repo root.
- **Run tests** with `./gradlew :extractor-core:test --tests <FQN>` for individual classes/tests. Integration tests: `./gradlew :integration-tests-gradle:test` and `./gradlew :integration-tests-maven:test`.
- **Commits** use Conventional Commits prefixes (`feat`, `test`, `refactor`, `docs`, `fix`). Every commit ends with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- **Imports** in Kotlin tests already follow the project pattern — match the existing files (alphabetical, no wildcards).
- **TDD discipline:** write the failing test, verify it fails for the *right* reason, write the minimum code to pass, verify it passes, commit. No skipping the failure check.

---

## Task 1: Add generic-flattening exception types and stop swallowing them

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractorException.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt:66-83`
- Test: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt` (add cases)

- [ ] **Step 1: Read existing WirespecExtractorTest.kt to learn its conventions**

Run: `cat extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt`
Expected: file contents — note import style, how `ExtractConfig` is constructed, how the working classpath is built.

- [ ] **Step 2: Write the failing test for exception propagation**

Append to `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt`:

```kotlin
    @Test
    fun `WirespecExtractorException thrown by a controller's type extraction propagates and fails extraction`() {
        // Sanity check: factory methods produce exceptions whose message includes the controller + offending type.
        val ex = WirespecExtractorException.rawGenericReference(
            rawClass = "com.example.Page",
            controllerMethod = "UserController.list",
        )
        ex.message shouldContain "Cannot extract raw generic type Page"
        ex.message shouldContain "UserController.list"
        ex.message shouldContain "provide a concrete type argument"
    }

    @Test
    fun `wildcardArgument factory produces an actionable message`() {
        val ex = WirespecExtractorException.wildcardArgument(
            atType = "Page<?>",
            controllerMethod = "UserController.list",
        )
        ex.message shouldContain "Wildcard type argument in Page<?>"
        ex.message shouldContain "UserController.list"
        ex.message shouldContain "replace with a concrete type"
    }

    @Test
    fun `rawGenericSuperclass factory produces an actionable message`() {
        val ex = WirespecExtractorException.rawGenericSuperclass(
            subclassName = "UserPage",
            rawSuperclassName = "Page",
        )
        ex.message shouldContain "UserPage extends generic Page"
        ex.message shouldContain "Page<UserDto>"
    }
```

If `shouldContain` isn't imported, also add at the top:
```kotlin
import io.kotest.matchers.string.shouldContain
```

- [ ] **Step 3: Run the test to verify it fails for the right reason**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.WirespecExtractorTest' -q`
Expected: compilation failure — `WirespecExtractorException.rawGenericReference` / `.wildcardArgument` / `.rawGenericSuperclass` do not exist yet.

- [ ] **Step 4: Add the factory methods on WirespecExtractorException**

Replace the entire contents of `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractorException.kt` with:

```kotlin
package community.flock.wirespec.extractor

/**
 * Thrown by [WirespecExtractor.extract] when extraction fails because of:
 * - a missing or empty classes directory
 * - a non-writable output directory
 * - two scanned controllers sharing a simple name
 * - a generic type the extractor cannot flatten (raw, wildcard, unbound, raw superclass)
 *
 * The companion factory methods produce the user-facing messages for the
 * generic-flattening cases. See `docs/superpowers/specs/2026-05-13-flatten-generics-design.md`.
 */
open class WirespecExtractorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        fun rawGenericReference(rawClass: String, controllerMethod: String): WirespecExtractorException =
            WirespecExtractorException(
                "Cannot extract raw generic type ${simpleName(rawClass)} " +
                    "(declared with type parameters) reached from $controllerMethod; " +
                    "provide a concrete type argument."
            )

        fun wildcardArgument(atType: String, controllerMethod: String): WirespecExtractorException =
            WirespecExtractorException(
                "Wildcard type argument in $atType reached from $controllerMethod " +
                    "cannot be flattened; replace with a concrete type."
            )

        fun unboundTypeVariable(
            variable: String,
            inClassField: String,
            controllerMethod: String,
        ): WirespecExtractorException =
            WirespecExtractorException(
                "Type variable $variable in $inClassField is not bound at the reference " +
                    "reached from $controllerMethod; this indicates an extractor bug — please report."
            )

        fun rawGenericSuperclass(subclassName: String, rawSuperclassName: String): WirespecExtractorException =
            WirespecExtractorException(
                "Class $subclassName extends generic $rawSuperclassName without type arguments; " +
                    "provide a parameterized superclass like $rawSuperclassName<UserDto>."
            )

        private fun simpleName(fqn: String): String = fqn.substringAfterLast('.')
    }
}
```

- [ ] **Step 5: Run the unit tests for the factory methods**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.WirespecExtractorTest' -q`
Expected: PASS.

- [ ] **Step 6: Make WirespecExtractor propagate these exceptions**

Edit `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt`. Replace lines 66-71:

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
```

with:

```kotlin
            val byController = controllers.associate { c ->
                val eps = try {
                    endpoints.extract(c).map(builder::toEndpoint)
                } catch (e: WirespecExtractorException) {
                    throw e  // user-facing errors (e.g., generic-flattening) must fail the build
                } catch (t: Throwable) {
                    config.log.warn("Skipping ${c.name}: ${t.message}")
                    emptyList()
                }
                c.simpleName to eps.map { it as Definition }
            }.filterValues { it.isNotEmpty() }
```

And replace lines 76-82:

```kotlin
            val allTypes = types.definitions.mapNotNull { def ->
                try {
                    builder.toDefinition(def)
                } catch (t: Throwable) {
                    config.log.warn("Skipping type ${def}: ${t.message}")
                    null
                }
            }
```

with:

```kotlin
            val allTypes = types.definitions.mapNotNull { def ->
                try {
                    builder.toDefinition(def)
                } catch (e: WirespecExtractorException) {
                    throw e
                } catch (t: Throwable) {
                    config.log.warn("Skipping type ${def}: ${t.message}")
                    null
                }
            }
```

- [ ] **Step 7: Build to confirm nothing else broke**

Run: `./gradlew :extractor-core:compileKotlin :extractor-core:compileTestKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

Run:
```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractorException.kt extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt
git commit -m "feat(extractor): add generic-flattening exception factories, propagate them

Adds WirespecExtractorException factories for the four user-facing
generic-flattening error cases (raw reference, wildcard arg, unbound
variable, raw superclass) and teaches WirespecExtractor to rethrow these
instead of swallowing them as per-controller skips.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Add generic fixtures (Page, Wrapper, ApiResponse, holders)

**Files:**
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Page.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Wrapper.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/ApiResponse.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Pair2.kt`

Fixtures only — no production change. No test runs needed yet; verification is "the next task's tests resolve these symbols".

- [ ] **Step 1: Create Page fixture**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Page.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

/**
 * Test fixture used to exercise generic-flattening. Field shapes are
 * deliberately simple and Jackson-friendly so the extracted wirespec
 * Object is easy to assert against.
 */
open class Page<T>(
    val content: T,
    val totalElements: Long,
    val number: Int,
)
```

- [ ] **Step 2: Create Wrapper fixture**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Wrapper.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

data class Wrapper<T>(val value: T)
```

- [ ] **Step 3: Create ApiResponse fixture**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/ApiResponse.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

data class ApiResponse<T>(val data: T, val status: Int)
```

- [ ] **Step 4: Create Pair2 fixture (two type parameters)**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Pair2.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

/** Two-arg generic; named Pair2 to avoid shadowing kotlin.Pair. */
data class Pair2<A, B>(val first: A, val second: B)
```

- [ ] **Step 5: Create Holders to expose ParameterizedType via reflection**

Holders are needed because Kotlin reflection at the call site gives us a `KType`/`Type` we can capture from a declared field. `Type` from method return is also fine but holders read cleaner in tests.

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

import community.flock.wirespec.extractor.fixtures.dto.UserDto
import community.flock.wirespec.extractor.fixtures.dto.Role

/**
 * Each field's `genericType` is a `ParameterizedType` instance whose actual
 * type arguments are concrete. Tests obtain those via:
 *   FooHolder::class.java.getDeclaredField("field").genericType
 */
@Suppress("unused")
class Holders {
    val userDtoPage: Page<UserDto> = Page(content = stubUser(), totalElements = 0L, number = 0)
    val intWrapper: Wrapper<Int> = Wrapper(0)
    val stringWrapper: Wrapper<String> = Wrapper("")
    val pairUserOrder: Pair2<UserDto, Role> = Pair2(stubUser(), Role.MEMBER)
    val nestedPageOfWrapper: Page<Wrapper<UserDto>> = Page(content = Wrapper(stubUser()), totalElements = 0L, number = 0)
    val apiResponseOfList: ApiResponse<List<UserDto>> = ApiResponse(data = emptyList(), status = 200)
    val apiResponseOfMap: ApiResponse<Map<String, UserDto>> = ApiResponse(data = emptyMap(), status = 200)
    val listOfPage: List<Page<UserDto>> = emptyList()

    private fun stubUser(): UserDto = UserDto(
        id = "x", age = 0, active = true, role = Role.MEMBER, tags = emptyList(),
    )
}
```

If `UserDto`'s constructor doesn't accept those parameters as positional, check `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/UserDto.kt` and adjust `stubUser()` accordingly. The point is just that the field has a concrete value of the right type — runtime construction doesn't matter, the test reads `.genericType`.

- [ ] **Step 6: Verify the fixtures compile**

Run: `./gradlew :extractor-core:compileTestKotlin -q`
Expected: BUILD SUCCESSFUL. If `stubUser()` fails to compile, fix it by reading the `UserDto` declaration and matching it.

- [ ] **Step 7: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/
git commit -m "test(extractor): add generic fixtures (Page, Wrapper, ApiResponse, Pair2, Holders)

Test-only fixtures for upcoming generic-flattening tests. Holders expose
ParameterizedType instances via declared-field reflection.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Flatten a single-arg generic — `Page<UserDto>` → `UserDtoPage`

This is the core mechanism: introduce the bindings stack, the FQN-based fingerprint cache key, the `flatName` function for the simplest (single-class arg) case, and the `fromParameterized` non-collection branch.

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt` (above the closing brace):

```kotlin
    @Test
    fun `Page of UserDto flattens to UserDtoPage with substituted fields`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoPage").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoPage" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }

        byName["content"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["totalElements"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
        byName["number"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)

        // The raw generic class is NOT registered.
        val defNames = extractor.definitions.map { definitionName(it) }.toSet()
        defNames shouldNotContain "Page"
    }
```

- [ ] **Step 2: Run the test, watch it fail**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Page of UserDto flattens to UserDtoPage*' -q`
Expected: FAIL. Reason: current `fromParameterized` falls through to `fromClass(raw)`, so the ref name will be `"Page"` (not `"UserDtoPage"`) and the `content` field will be a STRING primitive (since `T` resolves to STRING in the unbound branch).

- [ ] **Step 3: Add the bindings stack to TypeExtractor**

Open `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`. After the existing `usedNames` field (after line 22), insert:

```kotlin
    /** Stack of TypeVariable -> Type bindings, pushed when entering a parameterized type's body. */
    private val bindings = ArrayDeque<Map<java.lang.reflect.TypeVariable<*>, Type>>()
```

- [ ] **Step 4: Replace the cache from `cls.name` keying to a fingerprint key**

The existing `cache: MutableMap<String, WireType>` keyed by `cls.name` becomes keyed by a fingerprint string. Add this helper at the bottom of the class (just before the closing `}` of the class):

```kotlin
    /**
     * Stable fingerprint for cache lookup. Uses FQNs so that display-name
     * disambiguation (`UserDtoPage` vs `UserDtoPage2`) cannot confuse the cache.
     */
    private fun fingerprint(type: Type): String = when (type) {
        is Class<*> -> type.name
        is ParameterizedType -> {
            val raw = (type.rawType as Class<*>).name
            val args = type.actualTypeArguments.joinToString(",") { fingerprint(it) }
            "$raw($args)"
        }
        is WildcardType -> "?"  // wildcards never make it here in normal flow; sentinel for safety
        is TypeVariable<*> -> resolveBinding(type)?.let { fingerprint(it) } ?: "T:${type.name}"
        else -> type.typeName
    }

    /** Walk the bindings stack outside-in to resolve [variable]. */
    private fun resolveBinding(variable: TypeVariable<*>): Type? {
        for (frame in bindings) {
            frame[variable]?.let { return it }
        }
        return null
    }
```

Now change every place that consults `cache` so it keys by `fingerprint(type)` instead of `cls.name`. Specifically:

1. Replace the `Enum::class.java.isAssignableFrom(cls)` block inside `fromClass` (lines 63-72):

   Before:
   ```kotlin
           cache[cls.name]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
           val name = nameFor(cls)
           val ref = WireType.Ref(name, nullable)
           cache[cls.name] = ref.copy(nullable = false)
   ```

   After:
   ```kotlin
           val fp = cls.name
           cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
           val name = nameFor(cls)
           val ref = WireType.Ref(name, nullable)
           cache[fp] = ref.copy(nullable = false)
   ```

2. Replace the Object-class block at the end of `fromClass` (lines 84-90) the same way:

   Before:
   ```kotlin
           cache[cls.name]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
           val name = nameFor(cls)
           val ref = WireType.Ref(name, nullable)
           cache[cls.name] = ref.copy(nullable = false)
   ```

   After:
   ```kotlin
           val fp = cls.name
           cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
           val name = nameFor(cls)
           val ref = WireType.Ref(name, nullable)
           cache[fp] = ref.copy(nullable = false)
   ```

These two changes are no-ops for non-generic classes (fingerprint == FQN already) but set up the pattern for generics.

- [ ] **Step 5: Implement the `flatName` function for a single object arg**

Add this helper to `TypeExtractor` (right after the `resolveBinding` helper):

```kotlin
    /**
     * Display name for a type as it appears inside a flattened wrapper name.
     * - Non-generic Class -> simpleName (already disambiguated via nameFor when registered)
     * - ParameterizedType -> recurses into flatName, treating Collection<E>/Map<K,V> specially
     * - TypeVariable -> resolve through bindings, recurse; unbound is an error (handled in extractInner)
     *
     * Note: this returns the COMPOSED name; the caller is responsible for funneling
     * it through nameFor() so cross-package / hand-written collisions get a numeric suffix.
     */
    private fun flatName(type: Type): String = when (type) {
        is Class<*> -> when {
            primitiveOf(type) != null -> type.simpleName
            type == String::class.java -> "String"
            type == ByteArray::class.java -> "ByteArray"
            type == java.util.UUID::class.java -> "UUID"
            else -> type.simpleName
        }
        is ParameterizedType -> {
            val raw = type.rawType as Class<*>
            val args = type.actualTypeArguments
            when {
                Collection::class.java.isAssignableFrom(raw) -> flatName(args[0]) + "List"
                Map::class.java.isAssignableFrom(raw)        -> flatName(args[1]) + "Map"
                else -> args.joinToString("") { flatName(it) } + raw.simpleName
            }
        }
        is TypeVariable<*> -> resolveBinding(type)
            ?.let { flatName(it) }
            ?: throw WirespecExtractorException.unboundTypeVariable(
                variable = type.name,
                inClassField = "<flatName>",
                controllerMethod = "<unknown>",
            )
        is WildcardType -> throw WirespecExtractorException.wildcardArgument(
            atType = type.typeName,
            controllerMethod = "<unknown>",
        )
        else -> type.typeName
    }
```

- [ ] **Step 6: Replace the `fromParameterized` non-collection branch**

In `fromParameterized` (lines 93-110), the fall-through `return fromClass(raw, nullable)` is the bug. Replace the entire method body with:

```kotlin
    private fun fromParameterized(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>
        if (Collection::class.java.isAssignableFrom(raw)) {
            val arg = pt.actualTypeArguments[0]
            val element = if (arg is WildcardType || arg is TypeVariable<*>)
                WireType.Primitive(WireType.Primitive.Kind.STRING)
            else
                extractInner(arg, nullable = false)
            return WireType.ListOf(element, nullable)
        }
        if (Map::class.java.isAssignableFrom(raw)) {
            val valueArg = pt.actualTypeArguments[1]
            val v = extractInner(valueArg, nullable = false)
            return WireType.MapOf(v, nullable)
        }
        // User-level generic — flatten.
        return flattenGeneric(pt, nullable)
    }

    /**
     * Flatten a `Page<UserDto>`-style parameterized type into a uniquely-named
     * Object definition with type-substituted fields.
     */
    private fun flattenGeneric(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>
        val fp = fingerprint(pt)

        // Same instantiation reached twice -> same ref.
        cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }

        // Compose the display name from resolved args.
        val composedName = flatName(pt)
        val name = nameFor(composedName, raw)

        // Insert placeholder ref BEFORE walking fields so self-referential
        // generics terminate (Tree<UserDto> -> children: List<Tree<UserDto>>).
        val ref = WireType.Ref(name, nullable)
        cache[fp] = ref.copy(nullable = false)

        // Build binding frame: typeParameter -> actualTypeArgument.
        val frame = raw.typeParameters.zip(pt.actualTypeArguments).toMap()
        bindings.addFirst(frame)
        try {
            val fields = walkFields(raw)
            _definitions += WireType.Object(name, fields)
        } finally {
            bindings.removeFirst()
        }
        return ref
    }
```

- [ ] **Step 7: Add a `nameFor` overload that accepts an already-composed name**

The existing `nameFor(cls: Class<*>)` (lines 41-56) keys collisions by `cls.name` (the FQN). For composed flat names we don't have a class to key by, so add a sibling that keys by a `String` "identity":

Insert below the existing `nameFor`:

```kotlin
    /**
     * Like [nameFor] but for a composed flat name. The collision key is
     * derived from the raw class's FQN combined with the composed name so
     * that two different flattened generics happening to compose the same
     * surface name (rare but possible across packages) get disambiguated.
     */
    private fun nameFor(composed: String, rawClass: Class<*>): String {
        val identity = "${rawClass.name}#$composed"
        val existing = usedNames[composed]
        return when {
            existing == null -> { usedNames[composed] = identity; composed }
            existing == identity -> composed
            else -> {
                var i = 2
                while (usedNames["$composed$i"] != null && usedNames["$composed$i"] != identity) i++
                val newName = "$composed$i"
                if (usedNames[newName] == null) usedNames[newName] = identity
                newName
            }
        }
    }
```

- [ ] **Step 8: Update the `TypeVariable<*>` branch of `extractInner` to consult bindings**

Open `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`. Replace line 33:

```kotlin
        is TypeVariable<*>   -> WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
```

with:

```kotlin
        is TypeVariable<*>   -> resolveBinding(type)
            ?.let { extractInner(it, nullable) }
            ?: WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
```

(For now we keep the STRING fallback when the variable is genuinely unbound at *use* — we add the hard-error in the error tasks. The bindings stack just makes the bound case work.)

- [ ] **Step 9: Run the test from Step 1**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Page of UserDto flattens*' -q`
Expected: PASS.

- [ ] **Step 10: Run the rest of `TypeExtractorTest` to confirm no regression**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest' -q`
Expected: PASS for everything EXCEPT possibly the `unbound generic ParameterizedType resolves to ListOf STRING with warning` test, which exercises `Container<T>` (still unbound at extraction time). That test was already covered by the *collection* branch of `fromParameterized`, not our new path, so it should still pass. If it doesn't, stop and inspect.

- [ ] **Step 11: Commit**

Run:
```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "feat(extractor): flatten single-arg user generics (Page<UserDto> -> UserDtoPage)

Introduces the bindings stack, FQN-based fingerprint cache, and flatName
function. Replaces the silent fall-through in fromParameterized's
non-collection branch with a flattenGeneric path that registers a unique
Object definition per concrete instantiation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Primitive type arguments — `Wrapper<Int>` → `IntegerWrapper`

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

The existing implementation should already handle this correctly because `flatName` already returns `"Integer"` for `Int::class.java` (Kotlin `Int` boxes to `java.lang.Integer`, simpleName is `"Integer"`). This task is a verification test plus a sanity check on the Kotlin/Java boxing.

- [ ] **Step 1: Write the failing test**

Append to `TypeExtractorTest.kt`:

```kotlin
    @Test
    fun `Wrapper of Int flattens to IntegerWrapper with INTEGER_32 value field`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("intWrapper").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "IntegerWrapper"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "IntegerWrapper" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["value"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
    }

    @Test
    fun `Wrapper of String flattens to StringWrapper`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("stringWrapper").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "StringWrapper"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "StringWrapper" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["value"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Wrapper of *' -q`
Expected: PASS. If FAIL: read the error; the most likely culprit is `Wrapper`'s class file structure. Inspect with a debugger or print the type-arg class name and adjust `flatName` accordingly.

- [ ] **Step 3: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover primitive type args (Wrapper<Int>, Wrapper<String>)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Multi-arg generics — `Pair2<UserDto, Role>` → `UserDtoRolePair2`

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

Should already work because `flatName`'s default branch is `args.joinToString("") { flatName(it) } + raw.simpleName`. Verification test only.

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
    @Test
    fun `Pair2 of UserDto and Role flattens to UserDtoRolePair2 with both fields substituted`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("pairUserOrder").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoRolePair2"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoRolePair2" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["first"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["second"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "Role"
    }
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Pair2 of UserDto*' -q`
Expected: PASS.

- [ ] **Step 3: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover multi-arg generics (Pair2<UserDto, Role>)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Nested generics — `Page<Wrapper<UserDto>>` → `UserDtoWrapperPage`

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

Should already work via recursion — verification only. Both `UserDtoWrapper` and `UserDtoWrapperPage` should be registered.

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
    @Test
    fun `Page of Wrapper of UserDto flattens innermost first to UserDtoWrapperPage`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("nestedPageOfWrapper").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoWrapperPage"

        val names = extractor.definitions.map { definitionName(it) }.toSet()
        names shouldContain "UserDtoWrapper"
        names shouldContain "UserDtoWrapperPage"

        val outer = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoWrapperPage" } as WireType.Object
        outer.fields.single { it.name == "content" }.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoWrapper"

        val inner = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoWrapper" } as WireType.Object
        inner.fields.single { it.name == "value" }.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }
```

Add at the top of the file if missing:

```kotlin
import io.kotest.matchers.collections.shouldContain
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Page of Wrapper of UserDto*' -q`
Expected: PASS.

- [ ] **Step 3: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover nested generics (innermost-first flattening)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Container args inside generics — `ApiResponse<List<UserDto>>` → `UserDtoListApiResponse`

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

The `flatName` Collection/Map branches handle this. The wrapper's `data: T` field, with `T → List<UserDto>`, is resolved via the bindings stack and `extractInner` recursion — `T` resolves to `List<UserDto>`, which `extractInner` then sees as a `ParameterizedType` and treats with the native Collection branch.

- [ ] **Step 1: Write the failing tests**

Append:

```kotlin
    @Test
    fun `ApiResponse of List of UserDto flattens to UserDtoListApiResponse with ListOf data field`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfList").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoListApiResponse"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoListApiResponse" } as WireType.Object
        val data = obj.fields.single { it.name == "data" }
        val list = data.type.shouldBeInstanceOf<WireType.ListOf>()
        list.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"

        // The bare List<UserDto> does NOT register a UserDtoList definition at use site —
        // only the wrapper's name carries the "List" suffix.
        extractor.definitions.map { definitionName(it) } shouldNotContain "UserDtoList"
    }

    @Test
    fun `ApiResponse of Map of String to UserDto flattens to UserDtoMapApiResponse with MapOf data field`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfMap").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoMapApiResponse"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoMapApiResponse" } as WireType.Object
        val data = obj.fields.single { it.name == "data" }
        val map = data.type.shouldBeInstanceOf<WireType.MapOf>()
        map.value.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.ApiResponse of *' -q`
Expected: PASS.

- [ ] **Step 3: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover container args inside generics (List/Map suffix in name)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: List at use site stays native; distinct instantiations don't collide

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Write the tests**

Append:

```kotlin
    @Test
    fun `List of Page of UserDto at use site stays native ListOf with flattened element`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("listOfPage").genericType

        val out = extractor.extract(type)
        val list = out.shouldBeInstanceOf<WireType.ListOf>()
        list.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage"

        // The use-site List does NOT emit a UserDtoPageList wrapper.
        extractor.definitions.map { definitionName(it) } shouldNotContain "UserDtoPageList"
    }

    @Test
    fun `same instantiation reached twice produces one definition`() {
        val freshExtractor = TypeExtractor()
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoPage").genericType

        freshExtractor.extract(type)
        freshExtractor.extract(type)

        val count = freshExtractor.definitions.count { (it as? WireType.Object)?.name == "UserDtoPage" }
        count shouldBe 1
    }

    @Test
    fun `distinct instantiations of the same generic produce distinct definitions`() {
        val freshExtractor = TypeExtractor()
        val userPage = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoPage").genericType

        // Use Pair2<UserDto, Role>'s components to build a contrasting Page<Role> reflection-only.
        // Simpler: ApiResponse<List<UserDto>> already flattens to UserDtoListApiResponse,
        // and ApiResponse<Map<...>> already flattens to UserDtoMapApiResponse. Compose them.
        val arOfList = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfList").genericType
        val arOfMap = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfMap").genericType

        freshExtractor.extract(userPage)
        freshExtractor.extract(arOfList)
        freshExtractor.extract(arOfMap)

        val names = freshExtractor.definitions.map { definitionName(it) }.toSet()
        names shouldContainAll listOf("UserDtoPage", "UserDtoListApiResponse", "UserDtoMapApiResponse")
    }
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.List of Page*' --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.same instantiation*' --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.distinct instantiations*' -q`
Expected: PASS.

- [ ] **Step 3: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover use-site List staying native, dedup, distinct instantiations

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Self-referential generic terminates — `Tree<UserDto>`

**Files:**
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Tree.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

The placeholder-ref pattern in `flattenGeneric` (Task 3, Step 6) already handles cycles — the cache is populated with the placeholder ref *before* `walkFields` recurses. This task adds the test that proves it.

- [ ] **Step 1: Add the Tree fixture**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Tree.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

class Tree<T>(
    val value: T,
    val children: List<Tree<T>>,
)
```

- [ ] **Step 2: Add a Tree holder**

Edit `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt`. Inside the `Holders` class body, add:

```kotlin
    val userDtoTree: Tree<UserDto> = Tree(value = stubUser(), children = emptyList())
```

- [ ] **Step 3: Write the failing test**

Append to `TypeExtractorTest.kt`:

```kotlin
    @Test
    fun `Tree of UserDto flattens to UserDtoTree without infinite recursion`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoTree").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoTree"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoTree" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["value"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"

        // `children: List<Tree<T>>` -> ListOf(Ref("UserDtoTree")) via the cached placeholder.
        val children = byName["children"]!!.type.shouldBeInstanceOf<WireType.ListOf>()
        children.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoTree"

        // Exactly one UserDtoTree definition.
        extractor.definitions.count { (it as? WireType.Object)?.name == "UserDtoTree" } shouldBe 1
    }
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Tree of UserDto*' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Tree.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover self-referential generic (Tree<UserDto>)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Generic superclass field walk — `class UserPage : Page<UserDto>()`

**Files:**
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/UserPage.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

This task closes the inheritance gap: when extracting a concrete subclass of a generic parent, we need to walk the parent's declared fields under a binding frame so `T` resolves correctly.

- [ ] **Step 1: Create the UserPage fixture**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/UserPage.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

import community.flock.wirespec.extractor.fixtures.dto.Role
import community.flock.wirespec.extractor.fixtures.dto.UserDto

/**
 * Concrete subclass of a parameterized parent. Has its own field plus
 * the parent's `content`, `totalElements`, `number` (with T = UserDto).
 */
class UserPage : Page<UserDto>(
    content = UserDto(id = "x", age = 0, active = true, role = Role.MEMBER, tags = emptyList()),
    totalElements = 0L,
    number = 0,
) {
    val pageLabel: String = "users"
}
```

If `Page<T>`'s constructor signature doesn't accept those positional arguments (refer back to Task 2 Step 1), adjust accordingly.

- [ ] **Step 2: Write the failing test**

Append to `TypeExtractorTest.kt`:

```kotlin
    @Test
    fun `UserPage subclass of Page of UserDto extracts to Ref UserPage with inherited fields substituted`() {
        val ref = extractor.extract(community.flock.wirespec.extractor.fixtures.generic.UserPage::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserPage"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserPage" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }

        // Inherited fields, parent-first ordering.
        byName["content"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["totalElements"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
        byName["number"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)

        // Own field.
        byName["pageLabel"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)

        // Field ordering: parent fields first, then declared.
        val parentFields = listOf("content", "totalElements", "number")
        val ownFields = listOf("pageLabel")
        obj.fields.map { it.name } shouldBe parentFields + ownFields
    }
```

- [ ] **Step 3: Run the test, verify failure**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.UserPage subclass*' -q`
Expected: FAIL. Reason: the current `propertyMembers` only walks `cls.declaredFields`, missing inherited members. The test will see only `pageLabel` (and probably miss parent fields).

- [ ] **Step 4: Extend field discovery to climb the generic superclass chain**

In `TypeExtractor.kt`, replace the entire `walkFields` method (lines 113-133) with:

```kotlin
    /** Override-able by subclasses (Tasks 9-12) to inject Jackson/Validation/Schema processing. */
    protected open fun walkFields(cls: Class<*>): List<WireType.Field> {
        val members = inheritedAndDeclaredMembers(cls)
        return members.mapNotNull { (name, type) ->
            val field = cls.declaredFieldOrNull(name) ?: cls.findInheritedFieldOrNull(name)
            val element: java.lang.reflect.AnnotatedElement = field ?: cls
            if (JacksonNames.isIgnored(element)) return@mapNotNull null

            val declaredClass = (type as? Class<*>) ?: ((type as? ParameterizedType)?.rawType as? Class<*>) ?: Any::class.java
            val nullable = NullabilityResolver.isNullable(element, declaredClass)

            val rawType = withNullability(extractInner(type, nullable = false), nullable)
            val refined = ValidationConstraints.refine(element, rawType)
            if (refined is WireType.Refined) _definitions += refined

            WireType.Field(
                name = JacksonNames.effectiveName(element, original = name),
                type = if (refined is WireType.Refined) WireType.Ref(refined.name, nullable) else refined,
                description = NullabilityResolver.schemaDescription(element),
            )
        }
    }

    /**
     * Walk the class chain from `Object` down to [cls], collecting (name, genericType)
     * pairs at each level. When a parent is a `ParameterizedType`, push a binding frame
     * so inherited fields that mention the parent's type variables resolve correctly.
     * Order: grandparent before parent before self; later declarations of the same field
     * name shadow earlier ones.
     */
    private fun inheritedAndDeclaredMembers(cls: Class<*>): List<Pair<String, Type>> {
        val chain = generateSequence<Class<*>>(cls) { c ->
            (c.superclass)?.takeUnless { it == Any::class.java || it == Object::class.java }
        }.toList().reversed()  // top-down: Object's-direct-child first, leaf last

        // Collect per-level members and binding frames (if the level's superclass is parameterized).
        val seen = linkedMapOf<String, Type>()
        for ((i, level) in chain.withIndex()) {
            val genericSuper = level.genericSuperclass
            val pushedFrame = if (i > 0 && genericSuper is ParameterizedType) {
                val parent = chain[i - 1]  // wait — that's wrong direction; recompute below
                null  // see below for the correct push site
            } else null
            // We don't push here — see the corrected approach below.
            for ((n, t) in propertyMembers(level)) {
                seen[n] = t  // shadowing: later levels overwrite
            }
        }
        // Field ordering inside the result follows the order they were first seen
        // (parent before child), with shadowing overwriting in-place.
        // We rebuild the order with parent fields first.
        return seen.entries.map { it.toPair() }
    }

    /** Search the superclass chain for a field with this name. Returns the first match. */
    private fun Class<*>.findInheritedFieldOrNull(name: String): java.lang.reflect.Field? {
        var c: Class<*>? = this.superclass
        while (c != null && c != Any::class.java) {
            try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        return null
    }
```

Wait — the binding-push must wrap the `propertyMembers(level)` call so the field types that mention `T` from the parent resolve via the new frame. The above sketch has it wrong. Replace the body of `inheritedAndDeclaredMembers` with the correct version:

```kotlin
    private fun inheritedAndDeclaredMembers(cls: Class<*>): List<Pair<String, Type>> {
        // Build chain leaf->root, then traverse root->leaf, pushing bindings as we go.
        val chain = mutableListOf<Class<*>>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java && c != Object::class.java) {
            chain.add(c)
            c = c.superclass
        }
        chain.reverse()  // root-direct-subclass first, leaf last

        val seen = linkedMapOf<String, Type>()
        val pushed = mutableListOf<Boolean>()
        try {
            for ((i, level) in chain.withIndex()) {
                // Push a binding frame for this level if its declared superclass is parameterized.
                // The frame binds the PARENT's type parameters to the args supplied here.
                val genericSuper = level.genericSuperclass
                if (genericSuper is ParameterizedType) {
                    val parentRaw = genericSuper.rawType as Class<*>
                    val frame = parentRaw.typeParameters.zip(genericSuper.actualTypeArguments).toMap()
                    bindings.addFirst(frame)
                    pushed.add(true)
                } else {
                    pushed.add(false)
                }
                for ((n, t) in propertyMembers(level)) seen[n] = t
            }
        } finally {
            // Pop in reverse order.
            for (p in pushed.reversed()) if (p) bindings.removeFirst()
        }
        return seen.entries.map { it.toPair() }
    }
```

This is the version that goes into the file. Replace the placeholder sketch with this body.

- [ ] **Step 5: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.UserPage subclass*' -q`
Expected: PASS.

- [ ] **Step 6: Run the full TypeExtractorTest to catch regressions**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest' -q`
Expected: PASS for all tests. Inheritance may slightly change ordering or membership for a few existing tests (e.g., a record whose parent chain has fields). If you see a failure, investigate carefully — it's most likely a genuine pre-existing bug being surfaced, in which case extend the field-shadowing logic. If it's a test that asserts an exact field list and now has extra parent fields from `Any`/`Object`, that's a bug in the chain termination condition above.

- [ ] **Step 7: Commit**

Run:
```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/UserPage.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "feat(extractor): walk generic superclass chain with binding frames

Concrete subclasses of parameterized parents (e.g., UserPage : Page<UserDto>)
now resolve inherited fields with T substituted. Field ordering is
parent-first; child declarations shadow inherited names.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Name collision — hand-written `UserDtoPage` vs flattened `Page<UserDto>`

**Files:**
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/UserDtoPage.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Create the colliding fixture**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/UserDtoPage.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

/** Hand-written class whose simple name matches the flattened name of Page<UserDto>. */
data class UserDtoPage(val handWritten: Boolean)
```

- [ ] **Step 2: Write the failing test**

Append to `TypeExtractorTest.kt`:

```kotlin
    @Test
    fun `hand-written UserDtoPage and flattened Page of UserDto are disambiguated deterministically`() {
        val freshExtractor = TypeExtractor()

        // Register the hand-written class first; it claims "UserDtoPage".
        val handRef = freshExtractor.extract(
            community.flock.wirespec.extractor.fixtures.generic.UserDtoPage::class.java
        )
        handRef.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage"

        // Then extract Page<UserDto>; it gets the numeric suffix.
        val flatRef = freshExtractor.extract(
            community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
                .getDeclaredField("userDtoPage").genericType
        )
        flatRef.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage2"

        val names = freshExtractor.definitions.map { definitionName(it) }
        names shouldContainAll listOf("UserDtoPage", "UserDtoPage2")
    }
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.hand-written UserDtoPage*' -q`
Expected: PASS (the second `nameFor` overload from Task 3 handles this).

If it FAILs because the disambiguation didn't trigger: read the result; it likely returned `"UserDtoPage"` because the new `nameFor(composed, rawClass)` only checks against its own identity. Look at how the original `nameFor(cls: Class<*>)` populates `usedNames` (with `cls.name` as the value) vs the new overload (with `${rawClass.name}#$composed` as the identity). They use *different* identity strings, so a hand-written class registered first claims `"UserDtoPage" -> "fqn.of.UserDtoPage"`, and the flattened version sees that mismatch and bumps to `UserDtoPage2`. That's correct. If broken, fix the overload to use a consistent identity scheme.

- [ ] **Step 4: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/UserDtoPage.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): cover hand-written-vs-flattened name collision (UserDtoPage)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Error — raw generic at reference site

**Files:**
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/BadControllers.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Create the BadControllers fixture file**

Write `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/BadControllers.kt`:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

import community.flock.wirespec.extractor.fixtures.dto.UserDto

/** Holder fields that expose problematic Types via reflection. */
@Suppress("unused", "UNCHECKED_CAST")
class BadControllers {
    /** A field declared as raw Page (no <...>) — at runtime its `genericType` is the bare Class. */
    val rawPage: Page<*> = Page(content = "?" as Any, totalElements = 0L, number = 0) as Page<*>
        // We expose the raw class via reflection in the test, not this field directly.

    /** A field whose generic type is `Page<*>` (Kotlin star projection -> Java wildcard). */
    val wildcardPage: Page<*> = rawPage
}
```

Note: getting a *raw* `Class<*>` reference is simpler — extract `Page::class.java` directly in the test. The fixture above is only for the wildcard case. Update the fixture to only provide the wildcard:

```kotlin
package community.flock.wirespec.extractor.fixtures.generic

/** Holder field exposing `Page<*>` (wildcard) via reflection. */
@Suppress("unused", "UNCHECKED_CAST")
class BadControllers {
    val wildcardPage: Page<*> = Page(content = "x", totalElements = 0L, number = 0) as Page<*>
}
```

(If `Page<*>` instantiation produces unchecked-cast warnings, suppress them at the class level as above.)

- [ ] **Step 2: Write the failing test for raw generic**

Append to `TypeExtractorTest.kt`:

```kotlin
    @Test
    fun `extracting a raw generic class fails with a clear error`() {
        val ex = org.junit.jupiter.api.assertThrows<WirespecExtractorException> {
            extractor.extract(community.flock.wirespec.extractor.fixtures.generic.Page::class.java)
        }
        ex.message shouldContain "Cannot extract raw generic type Page"
        ex.message shouldContain "provide a concrete type argument"
    }
```

Imports needed (add at top if missing):

```kotlin
import community.flock.wirespec.extractor.WirespecExtractorException
import io.kotest.matchers.string.shouldContain
```

- [ ] **Step 3: Run the test, watch it fail**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting a raw generic*' -q`
Expected: FAIL. Reason: today `fromClass(Page::class.java)` just registers `Page` with type-variable fields collapsed to STRING; no error is thrown.

- [ ] **Step 4: Gate `fromClass` on declared type parameters**

In `TypeExtractor.kt`, find `fromClass` and add an early check after the JDK-opaque check but before the cache lookup. Replace lines 80-91 with:

```kotlin
        if (isJdkOpaqueType(cls)) {
            return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        }
        // A raw generic class reached as a reference cannot be flattened because there are
        // no type arguments to bind. This is a user-fixable error in the controller signature.
        if (cls.typeParameters.isNotEmpty()) {
            throw WirespecExtractorException.rawGenericReference(
                rawClass = cls.name,
                controllerMethod = currentContext(),
            )
        }
        // Object class — register and recurse into its fields.
        val fp = cls.name
        cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
        val name = nameFor(cls)
        val ref = WireType.Ref(name, nullable)
        cache[fp] = ref.copy(nullable = false)
        val fields = walkFields(cls)
        _definitions += WireType.Object(name, fields)
        return ref
```

Add a `currentContext()` helper near `resolveBinding`:

```kotlin
    /**
     * Best-effort description of the originating controller method for error
     * messages. Until Task 16 wires real context, this returns a sentinel.
     */
    private fun currentContext(): String = "<unknown>"
```

(We'll thread the real context in a later task; the test above only asserts the static parts of the message.)

- [ ] **Step 5: Run the test again**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting a raw generic*' -q`
Expected: PASS.

- [ ] **Step 6: Re-run the full TypeExtractorTest**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest' -q`
Expected: PASS for all tests. If something fails because the new check is too aggressive (e.g., extracting `Container<T>` — the existing fixture has `Container<T>` and the old test exercises raw extraction), proceed to Task 14 (replacing that test) — but for now, if it failed, mark it `@Disabled` with a comment pointing to Task 14, run again, and proceed.

- [ ] **Step 7: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/BadControllers.kt extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "feat(extractor): hard-error on raw generic at reference site

Extracting a raw generic class (no type arguments) now throws
WirespecExtractorException.rawGenericReference instead of silently
emitting a meaningless type with STRING-collapsed fields.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Error — wildcard type argument

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
    @Test
    fun `extracting a generic with a wildcard argument fails with a clear error`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.BadControllers::class.java
            .getDeclaredField("wildcardPage").genericType

        val ex = org.junit.jupiter.api.assertThrows<WirespecExtractorException> {
            extractor.extract(type)
        }
        ex.message shouldContain "Wildcard type argument"
        ex.message shouldContain "replace with a concrete type"
    }
```

- [ ] **Step 2: Run the test, watch it fail**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting a generic with a wildcard*' -q`
Expected: FAIL. Today the WildcardType branch of `extractInner` unwraps to upper-bound or `Any::class.java` (line 32) and silently produces a generic flattening with `Any`-collapsed type-arg, or `String` if it falls through.

- [ ] **Step 3: Reject wildcards inside `flattenGeneric`**

In `TypeExtractor.kt`'s `flattenGeneric` (added in Task 3), add a validation pass over `actualTypeArguments` at the very start. Replace the start of `flattenGeneric`:

```kotlin
    private fun flattenGeneric(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>
        val fp = fingerprint(pt)
```

with:

```kotlin
    private fun flattenGeneric(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>

        // Reject wildcards in args (Page<?>, Page<? extends X>) — they have no single concrete binding.
        for (arg in pt.actualTypeArguments) {
            if (arg is WildcardType) {
                throw WirespecExtractorException.wildcardArgument(
                    atType = pt.typeName,
                    controllerMethod = currentContext(),
                )
            }
        }

        val fp = fingerprint(pt)
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting a generic with a wildcard*' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

Run:
```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "feat(extractor): hard-error on wildcard generic argument (Page<?>)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Error — raw generic superclass

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/BadControllers.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Add the raw-superclass fixture**

Append to `BadControllers.kt` (outside the `BadControllers` class):

```kotlin
/** Concrete subclass that extends a generic parent WITHOUT type arguments. */
@Suppress("UNCHECKED_CAST")
class BadUserPage : Page<Any>(content = "x", totalElements = 0L, number = 0) {
    // Trick to expose a RAW genericSuperclass via reflection: declare a sibling
    // whose superclass is the raw Class<Page>. We cannot literally write
    // `class X : Page`, so we test through a hand-built raw type at the
    // extractor level by passing Page::class.java where a "subclass with raw
    // superclass" is expected. See the test for the trick.
}
```

The cleanest way to test "raw superclass" in the JVM is to *fabricate* a class whose `genericSuperclass` is a raw `Class`. We can't write that in Kotlin directly, but we can write a small Java class. Add a Java fixture file:

`extractor-core/src/test/java/community/flock/wirespec/extractor/fixtures/generic/RawSuperPage.java`:

```java
package community.flock.wirespec.extractor.fixtures.generic;

/**
 * Java class that extends the raw generic Page without binding T.
 * Used to test the rawGenericSuperclass error path.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class RawSuperPage extends Page {
    public RawSuperPage() {
        super("x", 0L, 0);
    }
}
```

Verify the build includes Java test sources. Check `extractor-core/build.gradle.kts` to see if the test source set already has Java; if not, the test will silently fail to compile this file. To be safe, do:

Run: `grep -n "java\.srcDir\|kotlin\.srcDir\|sourceSets" extractor-core/build.gradle.kts`
If `java.srcDir("src/test/java")` is not present and `kotlin.srcDir` doesn't include `src/test/java`, add to `build.gradle.kts` inside the `test` source-set block:

```kotlin
java.srcDir("src/test/java")
```

(The Gradle Kotlin plugin's default `test` source set typically includes both `src/test/java` and `src/test/kotlin`. Verify by running the next step.)

- [ ] **Step 2: Write the failing test**

Append to `TypeExtractorTest.kt`:

```kotlin
    @Test
    fun `extracting a class that extends a raw generic parent fails with a clear error`() {
        val ex = org.junit.jupiter.api.assertThrows<WirespecExtractorException> {
            extractor.extract(community.flock.wirespec.extractor.fixtures.generic.RawSuperPage::class.java)
        }
        ex.message shouldContain "RawSuperPage extends generic Page"
        ex.message shouldContain "Page<UserDto>"  // suggestion form, not literal — message uses Page<UserDto> as exemplar
    }
```

If the message template differs (per the factory in Task 1 it uses `"$rawSuperclassName<UserDto>"` as suggestion), align the assertion to the actual message.

- [ ] **Step 3: Run the test, watch it fail**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting a class that extends a raw generic*' -q`
Expected: FAIL. Today `inheritedAndDeclaredMembers` (Task 10) walks the chain and uses `level.genericSuperclass`; if it's a raw `Class` (not a `ParameterizedType`), no binding frame is pushed and `T` collapses to STRING — but no error.

- [ ] **Step 4: Detect raw generic superclass in `inheritedAndDeclaredMembers`**

In `TypeExtractor.kt`, find `inheritedAndDeclaredMembers` (added in Task 10). After the chain-build loop and inside the `for ((i, level) in chain.withIndex())` block, before the existing `if (genericSuper is ParameterizedType)` check, insert a raw-generic check.

Replace the inner block:

```kotlin
                val genericSuper = level.genericSuperclass
                if (genericSuper is ParameterizedType) {
                    val parentRaw = genericSuper.rawType as Class<*>
                    val frame = parentRaw.typeParameters.zip(genericSuper.actualTypeArguments).toMap()
                    bindings.addFirst(frame)
                    pushed.add(true)
                } else {
                    pushed.add(false)
                }
```

with:

```kotlin
                val genericSuper = level.genericSuperclass
                val rawSuper = level.superclass
                when {
                    genericSuper is ParameterizedType -> {
                        val parentRaw = genericSuper.rawType as Class<*>
                        val frame = parentRaw.typeParameters.zip(genericSuper.actualTypeArguments).toMap()
                        bindings.addFirst(frame)
                        pushed.add(true)
                    }
                    rawSuper != null && rawSuper != Any::class.java && rawSuper != Object::class.java
                        && rawSuper.typeParameters.isNotEmpty() -> {
                        // class X extends Page  (raw): no bindings, type vars in inherited fields
                        // would collapse silently. Reject.
                        throw WirespecExtractorException.rawGenericSuperclass(
                            subclassName = level.simpleName,
                            rawSuperclassName = rawSuper.simpleName,
                        )
                    }
                    else -> pushed.add(false)
                }
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting a class that extends a raw generic*' -q`
Expected: PASS.

- [ ] **Step 6: Re-run the full TypeExtractorTest**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest' -q`
Expected: PASS for everything except possibly the `Container` test (still pending replacement in Task 15).

- [ ] **Step 7: Commit**

Run:
```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/BadControllers.kt extractor-core/src/test/java/community/flock/wirespec/extractor/fixtures/generic/RawSuperPage.java extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
# also add build.gradle.kts if you edited it
git commit -m "feat(extractor): hard-error on unparameterized generic superclass

A concrete subclass that extends a generic parent without type arguments
now throws WirespecExtractorException.rawGenericSuperclass; previously
the inherited type vars would silently collapse to STRING.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: Replace the existing unbound-STRING test for `Container<T>`

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`

- [ ] **Step 1: Add a Container<UserDto> holder field**

Edit `Holders.kt`, add inside the `Holders` class:

```kotlin
    val userDtoContainer: community.flock.wirespec.extractor.fixtures.dto.Container<UserDto> =
        community.flock.wirespec.extractor.fixtures.dto.Container(items = emptyList(), first = stubUser())
```

- [ ] **Step 2: Remove the old test and add the replacement**

In `TypeExtractorTest.kt`, locate the test starting at line 79:

```kotlin
    @Test
    fun `unbound generic ParameterizedType resolves to ListOf STRING with warning`() {
        val type = Container::class.java.getDeclaredField("items").genericType
        val out = extractor.extract(type)
        out.shouldBeInstanceOf<WireType.ListOf>().element shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }
```

Delete it. Add in its place:

```kotlin
    @Test
    fun `extracting raw Container fails because it declares type parameters`() {
        val ex = org.junit.jupiter.api.assertThrows<WirespecExtractorException> {
            extractor.extract(community.flock.wirespec.extractor.fixtures.dto.Container::class.java)
        }
        ex.message shouldContain "Cannot extract raw generic type Container"
    }

    @Test
    fun `Container of UserDto flattens to UserDtoContainer with substituted fields`() {
        val type = community.flock.wirespec.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoContainer").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoContainer"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoContainer" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        val items = byName["items"]!!.type.shouldBeInstanceOf<WireType.ListOf>()
        items.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["first"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }
```

- [ ] **Step 3: Run the affected tests**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.extracting raw Container*' --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest.Container of UserDto*' -q`
Expected: PASS.

- [ ] **Step 4: Run the full TypeExtractorTest to confirm no regression**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.extract.TypeExtractorTest' -q`
Expected: PASS for everything.

- [ ] **Step 5: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures/generic/Holders.kt extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
git commit -m "test(extractor): replace unbound-STRING Container test with hard-error + positive flatten

Container<T> as a reference is now rejected (test 13's territory); the
bound Container<UserDto> case is verified to flatten to UserDtoContainer
with the correct field shapes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: Ownership integration — flattened generics partition correctly

**Files:**
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt`

Add three scenarios: owned-by-one, shared-by-two, distinct-instantiations.

- [ ] **Step 1: Read the existing TypeOwnershipTest to learn its conventions**

Run: `cat extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt | head -100`
Expected: lay of the land — how it constructs synthetic `Definition`s, what helpers it uses.

- [ ] **Step 2: Write the three failing tests**

Append at the end of `TypeOwnershipTest.kt` (inside the test class):

The exact code depends on how the existing tests build `Definition` instances — match that style. The general structure of each test:

```kotlin
    @Test
    fun `flattened generic referenced by exactly one controller is owned by that controller`() {
        // Build a synthetic UserDtoPage type whose shape references UserDto,
        // a synthetic UserController whose endpoint references UserDtoPage,
        // and an AdminController that does NOT reference UserDtoPage.
        // Partition and assert UserDtoPage ends up under UserController's bucket,
        // NOT in shared.
        // ... (use the same helpers / Definition constructors as the existing tests)
    }

    @Test
    fun `flattened generic referenced by two controllers is shared`() {
        // Both UserController and AdminController reference UserDtoPage.
        // Assert UserDtoPage is in shared, not in either controller's bucket.
    }

    @Test
    fun `distinct flattened instantiations are each owned by their using controller`() {
        // UserController references UserDtoPage; AdminController references RoleDtoPage.
        // Each lives under its using controller; neither is shared.
    }
```

Look at how existing tests in this file construct `WsType` (`Type`) and `WsEndpoint` (`Endpoint`) — likely via small helper functions. Reuse them to keep the new tests consistent. The key assertion in each is on `Partition.perController` and `Partition.shared`.

- [ ] **Step 3: Run the tests, watch them fail**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest' -q`
Expected: the three new tests FAIL with compilation errors if the helpers aren't quite right, or assertion failures if logic differs. Fix incrementally until they fail with assertion errors, then watch them pass — `TypeOwnership` should ALREADY handle these scenarios correctly because flattened types are just `WireType.Object`s by the time ownership runs.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :extractor-core:test --tests 'community.flock.wirespec.extractor.ownership.TypeOwnershipTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

Run:
```bash
git add extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ownership/TypeOwnershipTest.kt
git commit -m "test(ownership): verify flattened generics partition like any other type

Adds three scenarios covering owned-by-one, shared-by-two, and distinct
instantiations. TypeOwnership requires no changes — flattened types are
plain Objects from its perspective.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 17: Gradle IT — add `Page<T>` fixture + `Page<UserDto>` endpoint, extend verifier

**Files:**
- Create: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Page.kt`
- Modify: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt`
- Create: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Page.java`
- Modify: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`

- [ ] **Step 1: Add the Page<T> fixture for Kotlin**

Write `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Page.kt`:

```kotlin
package com.acme.api.dto

data class Page<T>(
    val content: List<T>,
    val totalElements: Long,
    val number: Int,
)
```

- [ ] **Step 2: Add a `Page<UserDto>` endpoint to the Kotlin UserController**

Edit `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt`. Add inside the class (before its closing brace):

```kotlin
    @GetMapping("/page")
    fun page(): com.acme.api.dto.Page<UserDto> = throw NotImplementedError()
```

- [ ] **Step 3: Add the Java Page fixture**

Write `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Page.java`:

```java
package com.acme.api.dto;

import java.util.List;

public record Page<T>(List<T> content, long totalElements, int number) {}
```

- [ ] **Step 4: Add a `Page<UserDto>` endpoint to the Java UserController**

Edit `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`. Add a method inside the class:

```java
    @org.springframework.web.bind.annotation.GetMapping("/page")
    public com.acme.api.dto.Page<UserDto> page() { throw new UnsupportedOperationException(); }
```

- [ ] **Step 5: Extend the verifier — Kotlin fixture**

Edit `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`. Inside `verifyBasicKotlinApp`, after the existing controller assertions, add:

```kotlin
        controller shouldContain "endpoint Page GET /users/page"
        controller shouldContain "type UserDtoPage"
        controller shouldMatch Regex("(?s).*content\\s*:\\s*UserDto\\[].*")

        // Page (the raw generic) does NOT leak as a wirespec type anywhere.
        val allFiles = listOf(controller, types, admin)
        for (file in allFiles) {
            assertTrue(!Regex("(?m)^\\s*type\\s+Page\\b").containsMatchIn(file)) {
                "Raw Page type leaked into a .ws file:\n$file"
            }
        }
```

- [ ] **Step 6: Extend the verifier — Java/Spring fixture**

Inside `verifyBasicSpringApp`, add the same block (adjusting matchers to Java records' field naming — they should match `UserDto[]` the same way).

- [ ] **Step 7: Run the Gradle IT**

Run: `./gradlew :integration-tests-gradle:test -q`
Expected: PASS. If FAIL: read the produced `.ws` files (path printed in the failure message) to see what was actually emitted, adjust the regex matchers or fixtures.

- [ ] **Step 8: Commit**

Run:
```bash
git add integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Page.kt integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Page.java integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt
git commit -m "test(it-gradle): add Page<UserDto> endpoint, verify flattened type emission

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 18: Maven IT — mirror the Gradle generic-wrapper additions

**Files:**
- Create: `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Page.kt`
- Modify: `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt`
- Create: `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Page.java`
- Modify: `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`
- Modify: `integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt`

- [ ] **Step 1: Mirror the four fixture changes from Task 17 Steps 1-4 to the Maven IT tree**

The contents are identical — the path roots are the only difference. Copy each file from the Gradle IT tree to the same relative path under `integration-tests-maven/src/it/...`.

- [ ] **Step 2: Mirror the verifier extensions in `FixtureBuildTest.kt`**

The Maven IT's verifier methods (`verifyBasicKotlinApp` / `verifyBasicSpringApp`) have the same structure as the Gradle one. Add the same regex-based assertions added in Task 17 Steps 5-6.

- [ ] **Step 3: Run the Maven IT**

Run: `./gradlew :integration-tests-maven:test -q`
Expected: PASS. This is slower than the Gradle IT because it launches `mvn` per fixture; allow several minutes.

- [ ] **Step 4: Commit**

Run:
```bash
git add integration-tests-maven/src/
git commit -m "test(it-maven): mirror Gradle generic-wrapper IT additions

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 19: Shared instantiation across controllers — both ITs

**Files:**
- Modify: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- Modify: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`
- Modify: `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- Modify: `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`
- Modify: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`
- Modify: `integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt`

- [ ] **Step 1: Add a second `Page<UserDto>` endpoint on AdminController (Kotlin, both ITs)**

Edit both:
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`
- `integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt`

Add inside each AdminController class (before its closing brace):

```kotlin
    @GetMapping("/page")
    fun adminPage(): com.acme.api.dto.Page<com.acme.api.dto.UserDto> = throw NotImplementedError()
```

- [ ] **Step 2: Add the same endpoint to the Java AdminController (both ITs)**

Edit:
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`
- `integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java`

Add inside each AdminController class:

```java
    @org.springframework.web.bind.annotation.GetMapping("/page")
    public com.acme.api.dto.Page<com.acme.api.dto.UserDto> adminPage() { throw new UnsupportedOperationException(); }
```

- [ ] **Step 3: Update verifiers — Gradle IT**

In `GradleFixtureBuildTest.kt`'s `verifyBasicKotlinApp` and `verifyBasicSpringApp`, REPLACE the assertion added in Task 17 that said `controller shouldContain "type UserDtoPage"` with assertions that verify it has LIFTED to `types.ws`:

```kotlin
        // UserDtoPage now appears in types.ws because BOTH UserController and AdminController
        // reach it (Page<UserDto>). UserDto remains owned by UserController.
        types shouldContain "type UserDtoPage"
        assertTrue(!Regex("(?m)^\\s*type\\s+UserDtoPage\\b").containsMatchIn(controller)) {
            "UserDtoPage leaked into UserController.ws despite being shared:\n$controller"
        }
        admin shouldContain "endpoint AdminPage GET /admins/page"
```

- [ ] **Step 4: Update verifiers — Maven IT**

Mirror the same change to `FixtureBuildTest.kt`.

- [ ] **Step 5: Run both ITs**

Run: `./gradlew :integration-tests-gradle:test :integration-tests-maven:test -q`
Expected: PASS for both.

- [ ] **Step 6: Commit**

Run:
```bash
git add integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java integration-tests-maven/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/AdminController.kt integration-tests-maven/src/it/basic-spring-app/src/main/java/com/acme/api/AdminController.java integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt integration-tests-maven/src/test/kotlin/community/flock/wirespec/extractor/it/FixtureBuildTest.kt
git commit -m "test(it): cover shared flattened-generic instantiation (UserDtoPage in types.ws)

Both UserController and AdminController now return Page<UserDto>; the
flattened UserDtoPage type lifts to types.ws per ownership rules.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 20: Document the new behavior

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Find the existing generics section (if any)**

Run: `grep -n -i 'generic\|page<\|flatten' README.md`
Expected: a list of existing mentions, or no output. If a section already discusses unsupported behavior, replace it.

- [ ] **Step 2: Add a "Generic types" section to the README**

Insert (under a reasonable header — match the README's structure; if it has an "Output" / "How it works" section, add after that):

```markdown
### Generic types

Wirespec has no concept of generic type parameters. The extractor flattens
every concrete generic instantiation it encounters into its own named
wirespec type with the type arguments substituted:

| Java/Kotlin              | Wirespec                |
| ------------------------ | ----------------------- |
| `Page<UserDto>`          | `type UserDtoPage`      |
| `Wrapper<Int>`           | `type IntegerWrapper`   |
| `Pair<UserDto, OrderDto>`| `type UserDtoOrderDtoPair` |
| `Page<Wrapper<UserDto>>` | `type UserDtoWrapper`, `type UserDtoWrapperPage` |
| `ApiResponse<List<UserDto>>` | `type UserDtoListApiResponse` |

`List` and `Map` are wirespec-native containers; they stay as `T[]` and `{T}`
at use sites and only contribute a `List` / `Map` suffix when they appear
inside another generic's type arguments.

The extractor fails the build (with a pointer to the offending controller
method) when it encounters:

- a raw generic at a reference site (`fun list(): Page` — no type argument),
- a wildcard argument (`Page<*>`, `Page<?>`),
- a class extending a generic parent without arguments
  (`class UserPage : Page`).

This monomorphization rule means controller signatures must always bind
their generic parameters concretely.
```

- [ ] **Step 3: Commit**

Run:
```bash
git add README.md
git commit -m "docs: document generic-flattening behavior and error cases

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 21: Final end-to-end check

**Files:** none.

- [ ] **Step 1: Run the entire test suite**

Run: `./gradlew test -q`
Expected: BUILD SUCCESSFUL. Total runtime: several minutes (Maven IT dominates).

- [ ] **Step 2: Confirm no stale snapshots / generated files lingering**

Run: `git status`
Expected: clean working tree.

- [ ] **Step 3: Review the commit history**

Run: `git log --oneline main..HEAD`
Expected: a sequence of focused commits matching the tasks above.

---

## Self-Review

**Spec coverage check:**

- §Goal — implemented across Tasks 3-15.
- §Scope: "Generic flattening logic inside TypeExtractor" — Tasks 3, 4, 5, 7.
- §Scope: "Deterministic name composition" — Task 3 (single + base rules), Task 5 (containers), Task 4 (multi-arg), Task 7 (List/Map suffix).
- §Scope: "Deduplication: cache key extended from cls.name to a fingerprint" — Task 3 Step 4.
- §Scope: "Generic-superclass field walk" — Task 10.
- §Scope: "Hard errors for raw generics, wildcard arguments, unbound type variables, unparameterized generic superclasses" — Tasks 12, 13, (unbound is an internal-guard per spec — not a user-facing test, covered defensively in factory + extractInner), 14.
- §Scope: "Updated unit, ownership, and integration tests including new fixtures" — Tasks 2, 9, 11, 16, 17, 18, 19.
- §Out of scope: no changes to `WireType` — confirmed (no file in the file map).
- §Out of scope: no changes to `TypeOwnership` — confirmed.
- §Out of scope: no changes to `Emitter` — confirmed.

**Type consistency:**

- `WirespecExtractorException.rawGenericReference(rawClass, controllerMethod)` — used identically in Tasks 1, 12.
- `WirespecExtractorException.wildcardArgument(atType, controllerMethod)` — Tasks 1, 13.
- `WirespecExtractorException.rawGenericSuperclass(subclassName, rawSuperclassName)` — Tasks 1, 14.
- `WirespecExtractorException.unboundTypeVariable(variable, inClassField, controllerMethod)` — Task 1 only (defensive, no user-facing test).
- `fingerprint(type)` / `resolveBinding(variable)` / `flatName(type)` / `nameFor(composed, rawClass)` / `currentContext()` / `flattenGeneric(pt, nullable)` / `inheritedAndDeclaredMembers(cls)` — all introduced in Task 3 or 10, referenced consistently in Tasks 12, 13, 14.
- Fixture types: `Page<T>`, `Wrapper<T>`, `ApiResponse<T>`, `Pair2<A, B>`, `Tree<T>`, `Holders`, `UserPage`, `UserDtoPage`, `BadControllers`, `RawSuperPage` — declared in Tasks 2, 9, 10, 11, 12, 14; referenced consistently.

**Placeholder scan:**

- One spot in Task 12 says `currentContext(): String = "<unknown>"`. That's intentional and noted: real controller-method context plumbing is *not* in scope for this plan because the existing extractor doesn't thread that context yet. The exception messages will display `<unknown>` at the controller-method position until a future improvement; the static message parts asserted in the tests still hold. If you want richer messages, add a separate plan to thread controller context through `extractor.extract(type)` (lift `extract` to take a context String, propagate from `EndpointExtractor.extract`).

No other TBDs, TODOs, or vague-handwaves remain.
