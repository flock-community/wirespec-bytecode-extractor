# Flatten generic types

**Date:** 2026-05-13
**Status:** Design — awaiting user review

## Problem

Wirespec has no concept of generics. Its type system is monomorphic: every
type reference must resolve to a concrete shape, with no `T` parameters and no
generic specializations at the reference site.

Today the extractor's `TypeExtractor.fromParameterized`
(`extractor-core/src/main/kotlin/community/flock/wirespec/bytecode/extractor/extract/TypeExtractor.kt:93`)
handles `Collection<T>` and `Map<K, V>` correctly — they become `WireType.ListOf`
and `WireType.MapOf` — but for **any other parameterized type** it falls
through to `fromClass(raw, nullable)` and silently drops the type arguments.
The consequences:

- `Page<UserDto>` and `Page<OrderDto>` both register a single `Page` definition,
  causing a collision that loses information.
- That `Page` definition has type-variable fields collapsed to `STRING`
  (via the `TypeVariable<*>` branch of `extractInner`), producing meaningless
  field types like `content: String` regardless of the real type argument.
- Common wrapper patterns (`ApiResponse<T>`, `Page<T>`, `Either<L, R>`, custom
  envelopes) cannot be modeled at all.

## Goal

Flatten every concrete generic instantiation encountered during extraction
into its own uniquely-named, top-level `WireType.Object` whose fields are the
raw generic class's fields with every type variable resolved against the
concrete arguments. Two different instantiations of the same generic class
become two different wirespec types; the same instantiation reached twice
becomes the same type, emitted once.

No generics survive into the emitted `.ws`. Built-in collection and map
containers stay as wirespec's native `ListOf` / `MapOf` — only user-level
generic *classes* (anything that is not `Collection` or `Map`) are flattened
into named wrappers.

## Scope

In scope:

- Generic flattening logic inside `TypeExtractor`: new behavior in
  `fromParameterized` for non-collection generics, plus a type-variable
  binding stack consulted by `extractInner`.
- Deterministic name composition (innermost-first, declaration order,
  Java simple names for primitives, `List`/`Map` suffix when a container
  appears inside another generic's args).
- Deduplication: cache key extended from `cls.name` to a fingerprint that
  includes resolved type arguments.
- Generic-superclass field walk: a concrete class extending a parameterized
  parent (e.g., `class UserPage : Page<UserDto>()`) resolves inherited fields
  through the bound type variables.
- Hard errors for raw generics, wildcard arguments, unbound type variables,
  and unparameterized generic superclasses, surfaced as
  `WirespecExtractorException` with context (`controller.method → type chain`).
- Updated unit, ownership, and integration tests including new fixtures.

Out of scope:

- Any change to `WireType` model (no new variants).
- Any change to `TypeOwnership` — flattened types flow through unmodified.
- Any change to `Emitter` — flattened types emit as ordinary objects.
- Any user-facing toggle to revert to the old (silently-dropping) behavior.
- Generic-method type parameters (`fun <T> get(): T`) — these are an unusual
  controller shape; they hit the unbound-type-variable error path.

## Design

### Architecture

Flattening lives entirely inside `TypeExtractor`. No new files, no new
pipeline stage. The downstream `TypeOwnership` and `Emitter` are untouched —
from their perspective, a flattened generic looks identical to any other
`WireType.Object` (same name semantics, same registration, same `Ref` at use
sites).

```
WirespecExtractor.extract
  ├── scan controllers
  ├── for each endpoint: extract params/body/response via TypeExtractor
  │       └── TypeExtractor recursively walks types
  │           ├── primitive / String / UUID / JDK-opaque  → Primitive
  │           ├── enum / object                            → Ref + register
  │           ├── Collection<T>, Map<K, V>                 → ListOf / MapOf  (unchanged)
  │           └── any other ParameterizedType              → FLATTEN  (new)
  ├── TypeOwnership.partition(byController, allTypes)        (unchanged)
  └── Emitter.write(...)                                     (unchanged)
```

Ownership integration follows for free: a flattened `UserDtoPage` referenced
by exactly one controller ends up under that controller's `.ws` file; the
same instantiation referenced by two or more controllers lifts to `types.ws`.
Same rules as for any other type.

### State extensions inside `TypeExtractor`

- **Cache key.** The existing `cache: MutableMap<String, WireType>` (keyed by
  `cls.name`, the raw FQN) becomes keyed by a *fingerprint string* built
  recursively from FQNs, not display names. For a non-generic class the
  fingerprint is its FQN. For a parameterized type it is
  `<rawFqn>(<arg1Fingerprint>,<arg2Fingerprint>,…)`; for a `Collection<E>`
  it is `java.util.List(<eFingerprint>)`; for a `Map<K, V>` it is
  `java.util.Map(<vFingerprint>)` (key omitted to match wirespec's
  `MapOf(V)` model). FQN-based keys keep cache lookup stable even when the
  composed display name is later disambiguated by `nameFor()` (so
  `UserDtoPage` and `UserDtoPage2` arising from a collision do not confuse
  the cache).
- **Bindings stack.** A new `private val bindings = ArrayDeque<Map<TypeVariable<*>, Type>>()`
  tracks the current substitution context. A frame is pushed before walking a
  parameterized type's fields (or a generic superclass's inherited fields)
  and popped on exit (try/finally). Resolution walks the stack outside-in so
  inner shadowing (a nested generic that reuses variable name `T`) is
  correct.
- **Context stack.** A parallel `private val context = ArrayDeque<String>()`
  records the chain from controller method down to the offending type, used
  exclusively to produce actionable error messages. Pushed/popped at the
  same boundaries as the bindings stack.
- **`usedNames` map.** Unchanged — the composed flattened name flows through
  the existing `nameFor()` machinery, so a hand-written `UserDtoPage` class
  colliding with a flattened `Page<UserDto>` produces `UserDtoPage` and
  `UserDtoPage2` deterministically.

### Naming algorithm

A pure, deterministic function `flatName(type, bindings)` invoked when a
`ParameterizedType` is encountered (and the raw type is neither `Collection`
nor `Map`). Naming is computed *after* arguments are recursively resolved,
so nested generics flatten innermost-first.

**Rules:**

1. Resolve each type argument to a display name:
   - `Class<*>` non-generic → its disambiguated simple name (`UserDto`).
   - `Class<*>` primitive / String / UUID / JDK-opaque → its Java simple name
     (`String`, `Integer`, `Long`, `Boolean`, `Double`, `LocalDateTime`, …).
     Kotlin `Int` is JVM `Integer`, so `Page<Int>` → `IntegerPage`.
   - `ParameterizedType` of a `Collection<E>` → recurse on `E` and append
     `"List"`: `List<UserDto>` → `"UserDtoList"`.
   - `ParameterizedType` of a `Map<K, V>` → recurse on `V` only (matches
     wirespec's `MapOf(V)` model) and append `"Map"`: `Map<String, Foo>` →
     `"FooMap"`.
   - `ParameterizedType` of any other generic → recurse to produce its
     flattened name (`Page<UserDto>` → `"UserDtoPage"`).
   - `TypeVariable<*>` → resolve through the bindings stack; unresolved is a
     hard error.
   - `WildcardType` (`Page<?>`, `Page<? extends X>`, Kotlin star) → hard
     error.

2. Concatenate resolved arg names in declaration order, then append the raw
   class's simple name:
   ```
   args[0] + args[1] + ... + args[n-1] + raw.simpleName
   ```

3. Pass the composed name through `nameFor()` for cross-package collision
   handling.

**Examples:**

```
Page<UserDto>                    → UserDtoPage
Pair<UserDto, OrderDto>          → UserDtoOrderDtoPair
Either<String, UserDto>          → StringUserDtoEither
Page<Wrapper<UserDto>>           → UserDtoWrapperPage          (UserDtoWrapper emitted first)
ApiResponse<List<UserDto>>       → UserDtoListApiResponse
ApiResponse<Map<String, Foo>>    → FooMapApiResponse
Box<Map<String, Page<Item>>>     → ItemPageMapBox              (ItemPage emitted first)
Page<Page<UserDto>>              → UserDtoPagePage             (UserDtoPage emitted first)
Page<Int>                        → IntegerPage
List<Page<UserDto>>  (use site)  → ListOf(Ref("UserDtoPage"))  (no UserDtoPageList emitted)
```

The `List`/`Map` suffix only appears when a container shows up *inside another
generic's args*. At use sites, `List<X>` and `Map<K, V>` remain wirespec
native containers — no wrapper definition is emitted.

### Field walking with bindings

The flattened type's fields are the raw class's fields with every type
variable resolved against the concrete arguments.

**Building a frame.** Java reflection exposes `cls.typeParameters` (the
declarations `T`) and `pt.actualTypeArguments` (the concrete types). Zip them
into a `Map<TypeVariable<*>, Type>`. The values are still raw `Type`s —
they may themselves be classes, parameterized types, wildcards, or other
variables (in `Box<List<UserDto>>`, `Box`'s `T` resolves to `List<UserDto>`).

**Variable resolution.** Extend the `TypeVariable<*>` branch of
`extractInner` to walk the bindings stack:
```kotlin
is TypeVariable<*> ->
    resolveBinding(type)
        ?.let { extractInner(it, nullable) }
        ?: throw WirespecExtractorException.UnboundTypeVariable(type, contextChain())
```
The existing fallback to `STRING` for type variables is removed.

**`fromParameterized` non-collection branch.** Replace the current
`return fromClass(raw, nullable)` fallback with:

1. Verify all arguments are concrete (no raw, no wildcards, no unbound
   vars) — otherwise hard error.
2. Compute the fingerprint and check the cache; if hit, return the cached
   `Ref`.
3. Compute the flattened name via `flatName(...)` + `nameFor(...)`.
4. Insert a placeholder `Ref` into the cache *before* walking fields (to
   make self-referential generics terminate).
5. Push a binding frame and a context frame; walk fields via the existing
   `walkFields(raw)`; pop on exit.
6. Register `WireType.Object(flatName, fields)` in `_definitions`.
7. Return the `Ref`.

**Fields that mention `T` indirectly.** `List<T>`, `Map<String, T>`,
`Wrapper<T>`, etc. resolve naturally because `extractInner` recurses into
each argument and eventually hits the `TypeVariable` branch, which consults
the binding stack. No new substitution machinery — substitution *is* "look
up the variable in bindings."

**Inheritance through generic superclasses.** A concrete class extending a
parameterized parent (`class UserPage : Page<UserDto>()`) is extracted under
its own simple name `UserPage`. To resolve inherited fields from `Page<T>`,
extend the field-discovery path:

- Walk inherited members first, recursing up the chain (grandparent, parent,
  then self), then append `cls.declaredFields` / record components last.
  This matches the conventional Jackson serialization order — parent fields
  precede child fields in the emitted `Object` — and gives a stable,
  predictable order in the generated `.ws`.
- For each step up the chain, inspect `cls.genericSuperclass`:
  - If it is a `ParameterizedType`, push a binding frame for the
    superclass's type variables (`Page`'s `T` ↦ `UserDto`) and recurse.
  - If it is a non-generic class, recurse with no new bindings until
    `Object.class`, then stop.
  - If it is a raw generic (`extends Page` without args), hard error
    (case 4 below).
- Field name collisions between parent and child (child shadowing parent)
  resolve to the child's declaration — the child's `declaredFields` entry
  wins. The parent's same-named field is omitted from the output. This
  matches both JVM field-shadowing semantics and Jackson's default
  behavior.

The JavaBean path (`cls.methods`) already sees inherited getters, so for
pure-bean styles much of this is already implicit; the explicit superclass
walk matters for Kotlin data classes and Java records that lean on declared
fields, and is what makes the type-variable resolution (`Page<T>`'s `T` ↦
`UserDto`) actually take effect for inherited members.

**Recursion safety.** Self-referential generics like
`class Tree<T>(val children: List<Tree<T>>)` reached as `Tree<UserDto>` are
handled by the placeholder-ref pattern in step 4 above, mirroring how
`fromClass` already handles cyclic non-generic types
(`TypeExtractor.kt:84-90`).

### Error handling

Hard errors fail extraction with a clear, actionable message bubbling up as
`WirespecExtractorException`, integrating with the existing Gradle/Maven
plugin error reporting and `ExtractLog`. No silent fallbacks.

**Cases:**

1. **Raw generic at a reference site.** Controller signature uses an
   unparameterized generic class that declares type parameters (`fun list():
   Page` where `Page` declares `<T>`).
   Message: `Cannot extract raw generic type Page (declared with type
   parameters <T>) reached from <controller>.<method>; provide a concrete
   type argument.`

2. **Wildcard / existential type argument.** `Page<?>`, `Page<? extends X>`,
   `Page<? super X>`, Kotlin `Page<*>`.
   Message: `Wildcard type argument in Page<?> reached from
   <controller>.<method> cannot be flattened; replace with a concrete type.`

3. **Unbound type variable during field walk.** A field's type is a
   `TypeVariable` that does not resolve through the bindings stack. In
   normal use this is unreachable — the gate at the entry of
   `fromParameterized` (case 1) and the wildcard check (case 2) filter out
   every user-facing path that could leave a variable unbound. Case 3
   exists as a defensive check that fires only on an internal bug (a frame
   that was supposed to be pushed wasn't, or shadowing was mis-handled).
   Message: `Type variable T in <Class>.<field> is not bound at the
   reference reached from <controller>.<method>; this indicates an
   extractor bug — please report.`

4. **Generic superclass missing arguments.** Class extends a generic parent
   raw (`class UserPage : Page`).
   Message: `Class UserPage extends generic Page without type arguments;
   provide a parameterized superclass like Page<UserDto>.`

**No partial output.** Existing all-or-nothing contract is preserved: a
hard error fails the whole extraction, the build fails, no `.ws` files are
emitted. Partial wirespec is worse than no wirespec for downstream codegen.

### Components

The full implementation touches one production file and several test files:

- **Modify:**
  `extractor-core/src/main/kotlin/community/flock/wirespec/bytecode/extractor/extract/TypeExtractor.kt`
  — extend `fromParameterized` non-collection branch, extend the
  `TypeVariable<*>` branch of `extractInner`, extend `walkFields` to climb
  generic superclasses, add `bindings`/`context` stacks, extend cache key.
- **Modify:**
  `extractor-core/src/main/kotlin/community/flock/wirespec/bytecode/extractor/WirespecExtractorException.kt`
  — add new exception subtypes (or detailed factory methods) for the four
  error cases.
- **Modify / add tests:** see Testing section.
- **Add integration fixtures:** a `PageController` (and reuse the existing
  `AdminController`) in both `integration-tests-gradle` and
  `integration-tests-maven` to verify ownership placement of flattened
  generics end-to-end.

## Testing

TDD throughout. Unit tests in
`extractor-core/src/test/kotlin/.../extract/TypeExtractorTest.kt`
(and a new `GenericFlatteningTest.kt` if the case list grows past ~15
entries). Ownership tests extend `TypeOwnershipTest.kt`. End-to-end coverage
lives in both integration-test projects.

**Unit-level (TypeExtractor):**

1. **Single object arg.** `Page<UserDto>` → `Ref("UserDtoPage")`; the
   `Object("UserDtoPage")` definition has `content: UserDto`,
   `totalElements: Long`, etc. `Page` is NOT registered.
2. **Single primitive arg.** `Wrapper<Int>` → `Ref("IntegerWrapper")`; the
   field of declared type `T` resolves to `INTEGER_32`.
3. **Multi-arg, declaration order.** `Pair<UserDto, OrderDto>` →
   `"UserDtoOrderDtoPair"`.
4. **Nested generic.** `Page<Wrapper<UserDto>>` → inner `Ref("UserDtoWrapper")`
   registered first, then `Ref("UserDtoWrapperPage")`; both definitions
   exist; `UserDtoWrapperPage.content` references `UserDtoWrapper`.
5. **List inside generic arg.** `ApiResponse<List<UserDto>>` →
   `"UserDtoListApiResponse"`; the wrapper's `data: List<T>` becomes
   `ListOf(Ref("UserDto"))`.
6. **Map inside generic arg.** `ApiResponse<Map<String, Foo>>` →
   `"FooMapApiResponse"`; wrapper's field is `MapOf(Ref("Foo"))`.
7. **List at use site stays native.** Method returns `List<Page<UserDto>>` →
   `ListOf(Ref("UserDtoPage"))`; `UserDtoPage` registered; no
   `UserDtoPageList` definition.
8. **Deduplication, same instantiation.** `Page<UserDto>` reached twice →
   one definition; cache returns same ref.
9. **Distinct instantiations.** `Page<UserDto>` and `Page<OrderDto>` in one
   scan → two definitions, no collision.
10. **Self-referential generic.** `Tree<UserDto>` where
    `Tree<T>` has `children: List<Tree<T>>` → placeholder-ref cache resolves
    the cycle; one `UserDtoTree` definition.
11. **Concrete subclass of generic.** `class UserPage : Page<UserDto>()` →
    `Ref("UserPage")`; inherited fields resolved via superclass-binding
    walk; `content: UserDto`.
12. **Name collision with hand-written class.** `class UserDtoPage(...)`
    plus a controller returning `Page<UserDto>` → first wins `"UserDtoPage"`,
    second becomes `"UserDtoPage2"` deterministically.

**Error-path:**

13. **Raw generic at reference site.** Method returns `Page` (raw) →
    `WirespecExtractorException` with controller method + class.
14. **Wildcard arg.** Method returns `Page<*>` / `Page<?>` → exception.
15. **Unparameterized generic superclass.** Class extends `Page` (raw) and
    is reached → exception.
16. **Replaces existing test.** The current
    `unbound generic ParameterizedType resolves to ListOf STRING with
    warning` at `TypeExtractorTest.kt:79` is removed. Replaced by an
    error-case assertion plus a positive `Container<UserDto>` flatten test
    asserting `UserDtoContainer` with `items: List<UserDto>` and
    `first: UserDto`.

**Ownership integration (`TypeOwnershipTest.kt`):**

17. **Flattened generic owned by one controller.** Controller A returns
    `Page<UserDto>`, controller B does not reference it → `UserDtoPage`
    appears under A's `.ws` file, NOT in `types.ws`.
18. **Flattened generic shared by two controllers.** A and B both return
    `Page<UserDto>` → `UserDtoPage` lifts to `types.ws`; `UserDto` also
    shared.
19. **Distinct instantiations across controllers.** A returns
    `Page<UserDto>`, B returns `Page<OrderDto>` → `UserDtoPage` owned by A,
    `OrderDtoPage` owned by B, neither shared.

**End-to-end (Gradle + Maven integration-test projects):**

20. **Custom generic wrapper.** Add a `PageController` (with `Page<UserDto>`
    and `Page<OrderDto>` endpoints) to both
    `integration-tests-gradle/src/main/.../` and `integration-tests-maven/...`.
    Commit expected `.ws` files; assert the controller's file emits its
    owned flattened types, no `Page` definition leaks, `types.ws` placement
    matches ownership rules.
21. **Shared instantiation.** Extend `AdminController` (already used for
    ownership coverage) to also reference `Page<UserDto>` — assert it lifts
    to `types.ws`.

**TDD ordering:**

- Test 1 → minimal `fromParameterized` branch + binding push/pop.
- Test 8 → fingerprint cache key.
- Tests 4 / 5 / 6 → nesting via recursion through bindings.
- Test 11 → generic-superclass field walk.
- Tests 13–15 → exception cases (with context chain).
- Integration tests last, with committed expected outputs.

**No regression elsewhere.** Every existing test in `extract/`, `ownership/`,
`emit/`, and the integration-test golden files must still pass after the
change, except for test 16 which is intentionally replaced.

## Open questions

None at this point. Naming, container behavior, primitive-arg handling,
nested resolution, and error policy are all resolved above. Implementation
will surface concrete cases (exact field shapes on Spring's `Page`, naming
of inherited Kotlin properties) handled by the existing
nullability/Jackson/validation passes, which need no new design work.
