# Types close to endpoints

**Date:** 2026-05-13
**Status:** Design — awaiting user review

## Problem

Today the extractor emits one `<Controller>.ws` per controller (endpoints only)
and a single `types.ws` containing **every** referenced DTO/enum/refined type,
even when that type is only referenced from one controller. The result is that
small APIs end up with a one-line controller file and a fat `types.ws` that
mixes concerns from controllers that don't otherwise interact.

## Goal

Place each DTO type next to the controller that uses it. Only types referenced
by **two or more** controllers should remain in the shared `types.ws`. A
controller-local type may itself be reached transitively (e.g., a field of a
type that is only used by one controller).

## Scope

In scope:

- New post-extraction analysis pass that partitions definitions by controller
  ownership.
- Per-controller `.ws` files emit endpoints first, then owned types.
- `types.ws` contains only types with 2+ controller owners; omitted when empty
  (existing behaviour for the empty case).
- README update reflecting the new layout.

Out of scope:

- Any change to the type extraction reflection layer (`TypeExtractor`).
- Any change to controller naming, collision detection, or output directory
  handling.
- Any user-facing toggle to revert to the old behaviour — the new layout
  replaces the old.

## Design

### Architecture

A new pure analyzer, `TypeOwnership`, runs after `TypeExtractor` and
`EndpointExtractor` and before `Emitter.write`:

```
WirespecExtractor.extract
  ├── scan controllers                            (unchanged)
  ├── extract endpoints per controller            (unchanged) → byController: Map<String, List<Definition>>
  ├── extract types                               (unchanged) → allTypes:    List<Definition>
  ├── TypeOwnership.partition(byController, allTypes)
  │       → Partition(perController, shared)
  └── Emitter.write(outputDir, perController, shared)
```

`perController[name]` contains the controller's existing endpoint definitions
followed by the type definitions it owns, in registration order.
`shared` contains type definitions referenced by 2+ controllers, in
registration order.

### `TypeOwnership` component

Located at
`extractor-core/src/main/kotlin/community/flock/wirespec/bytecode/extractor/ownership/TypeOwnership.kt`.

```kotlin
internal object TypeOwnership {
    data class Partition(
        val perController: Map<String, List<Definition>>,
        val shared: List<Definition>,
    )

    fun partition(
        endpointsByController: Map<String, List<Definition>>,
        allTypes: List<Definition>,
        onWarn: (String) -> Unit = {},
    ): Partition
}
```

### Algorithm

1. Build `definitionsByName: Map<String, Definition>` by extracting the
   identifier from each entry in `allTypes`.
2. For each controller, walk every `Endpoint` definition and collect the
   names of all `Reference.Custom` occurrences in:
   - `Endpoint.path` segments of type `Segment.Param`
   - `Endpoint.queries`
   - `Endpoint.headers`
   - `Endpoint.requests[*].content?.reference`
   - `Endpoint.responses[*].content?.reference`
   This yields `directRefs: Map<String /*controller*/, Set<String /*typeName*/>>`.
3. Compute the transitive closure per controller. Starting from
   `directRefs[controller]`, follow `Reference.Custom` references inside
   `Type.shape.value[*].reference`. Enums and Refined have no outgoing
   refs; recursion stops there. Use a visited set per controller to
   guarantee termination on cycles.
4. Invert into `ownersByType: Map<String, Set<String>>`.
5. Partition `allTypes`:
   - `|owners| == 1` → append to `perController[owner]`.
   - `|owners| >= 2` → append to `shared`.
   - `|owners| == 0` → defensive: append to `shared` and call
     `onWarn("Type X has no owning controller")`.
6. Within each output bucket, preserve the original order from `allTypes`
   (which is `TypeExtractor`'s registration order).

### Reference walker

A private helper inside `TypeOwnership` yields every `Reference.Custom` name
inside a given AST node:

- `Reference.Custom` → emit name.
- `Reference.Iterable` → recurse into `reference`.
- `Reference.Dict` → recurse into `reference`.
- Any other `Reference` subtype (primitives, etc.) → ignore.

This walker is exercised over `Endpoint` (step 2) and `Type.shape` (step 3).

### `WirespecExtractor` wiring

`WirespecExtractor.kt` already builds `byController` and a single
`sharedTypes` list. The change is:

```kotlin
val partition = TypeOwnership.partition(
    endpointsByController = byController,
    allTypes = sharedTypes,
    onWarn = config.log::warn,
)
val filesWritten = Emitter().write(
    outputDir = config.outputDirectory,
    controllerEndpoints = partition.perController,
    sharedTypes = partition.shared,
)
```

`Emitter.write` is **not** modified — its existing signature accepts
"definitions per controller" (treats them as endpoints today, but it just
renders whatever `Definition` list is passed) and "shared types". The
parameter name `controllerEndpoints` is now slightly misleading; rename it to
`controllerDefinitions` for clarity.

### File ordering

- Inside `<Controller>.ws`: endpoints first, then types — chosen so the API
  surface is prominent at the top of the file.
- Inside `types.ws`: registration order (unchanged).

### Empty cases

- All types are single-owner → `shared` is empty → `types.ws` is omitted
  (existing logic in `Emitter.write` already handles this).
- A controller has no endpoints → already filtered out by the existing
  `filterValues { it.isNotEmpty() }` in `WirespecExtractor`. A controller
  cannot own a type if it has no endpoints, so this stays consistent.
- A type with zero detected owners → logged as warning, placed in
  `types.ws` so it still gets emitted somewhere. This is a defensive
  branch; under current extraction logic every registered type is reachable
  from some endpoint.

### Error handling

`TypeOwnership` is pure and total: it never throws. The single soft-error
path (zero-owner type) goes through `onWarn`. Cycles in type references are
guarded by per-controller visited sets.

## Testing

### Unit tests — `TypeOwnershipTest`

Located at
`extractor-core/src/test/kotlin/.../ownership/TypeOwnershipTest.kt`.
Pure data-level tests over hand-built `Definition` lists; no reflection, no
Spring fixtures.

Cases:

1. **Single controller, single type** — `User` referenced by `ControllerA`'s
   response. Expect `User` in `perController["ControllerA"]`, `shared` empty.
2. **Two controllers share a type directly** — both reference `User`. Expect
   `User` in `shared`, neither controller owns it.
3. **Two controllers each with their own type** — `ControllerA` uses `Foo`,
   `ControllerB` uses `Bar`. Each type lives with its controller; `shared`
   empty.
4. **Transitive ownership** — `User { address: Address }`. Only `ControllerA`
   references `User`. Expect both `User` and `Address` owned by
   `ControllerA`.
5. **Transitive promotion to shared** — `User { address: Address }`.
   `ControllerA` references `User`, `ControllerB` references `Address`
   directly. Expect `User` owned by `ControllerA`, `Address` in `shared`.
6. **Enum in shared scope** — `Status` enum referenced by two controllers.
   Expect `Status` in `shared`.
7. **Refined type with single owner** — `EmailString` (refined) used only in
   `ControllerA`. Expect it owned by `ControllerA`.
8. **Cycle in references** — `Node { next: Node }`. Single controller uses
   `Node`. Expect `Node` owned by that controller, no infinite loop.
9. **Zero-owner type** — `Orphan` in `allTypes` but referenced by no
   endpoint. Expect it placed in `shared` and `onWarn` invoked with a
   message mentioning `Orphan`.
10. **Ordering preserved** — multiple owned types preserve `allTypes` order
    in the output list.

### Integration tests

Update existing Maven and Gradle integration fixtures
(`integration-tests-maven`, `integration-tests-gradle`) to assert the new
layout:

- Update existing snapshot assertions to expect the migrated definitions in
  controller files.
- Add at least one fixture that has two controllers sharing at least one
  type, so `types.ws` is non-empty and the assertion verifies the shared
  type lives there (not in either controller).
- Assert: for each `.ws` file produced, regex-match `type X` / `enum X` /
  `refined X` headers and compare against the expected per-file set.

### Snapshot impact

Any existing fixture whose `types.ws` previously contained
single-owner types will see those types migrate into the controller file.
This is the intended change. All snapshot assertions in
`integration-tests-*` need updating to reflect the new layout.

## Documentation

Update the **Output layout** section of `README.md`:

> - One `<ControllerName>.ws` per controller — endpoints, plus DTO/enum/refined
>   types referenced only by that controller.
> - One shared `types.ws` — DTO/enum/refined types referenced by two or more
>   controllers. Omitted when all types are controller-local.
> - The output directory is treated as a generated artifact: existing `*.ws`
>   files are deleted on each run; non-`.ws` files are left alone.

## Migration / Backwards compatibility

This is a breaking change in the generated output layout. Consumers that
depended on `types.ws` containing every DTO must be aware that types may
now live in a controller-named file. There is no compatibility flag — the
new layout is the only one.

## Implementation outline

1. Add `TypeOwnership.kt` with the `Partition` data class and `partition`
   function. Pure code, no dependencies beyond the Wirespec compiler AST.
2. Add `TypeOwnershipTest.kt` covering cases 1–10 above. Drive design with
   TDD: write each test red, implement, green.
3. Rename `Emitter.write`'s `controllerEndpoints` parameter to
   `controllerDefinitions`. Adjust the only caller.
4. Wire `TypeOwnership.partition` into `WirespecExtractor.extract` between
   the existing type/endpoint extraction and the emitter call.
5. Update integration test fixtures and assertions in
   `integration-tests-maven` and `integration-tests-gradle`. Add a
   two-controller scenario with a shared type.
6. Update `README.md` Output layout section.
7. Run full test suite (`./gradlew test integrationTest` or the project's
   equivalent) and confirm green.
