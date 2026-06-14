# wirespec-bytecode-extractor — Design

**Date:** 2026-05-12
**Status:** Approved for implementation planning

## Purpose

A Maven plugin that scans a Spring Boot application's compiled classes and emits
[Wirespec](https://wirespec.io) (`.ws`) files describing its HTTP endpoints and
DTO types. The goal is to give Spring projects a Wirespec contract derived from
the live code, with no manual translation step.

## Scope

### In scope (v1)

- Annotated Spring controllers: `@RestController`, and `@Controller` classes
  whose handler methods carry `@ResponseBody` (directly or via meta-annotations).
- The full `@RequestMapping` family: `@GetMapping`, `@PostMapping`,
  `@PutMapping`, `@PatchMapping`, `@DeleteMapping`, `@RequestMapping`.
- Path, query, header, and cookie parameters; request bodies; response bodies.
- Recursive DTO type extraction with Jackson, Bean Validation, and springdoc
  `@Schema` awareness.
- Java and Kotlin source projects.

### Out of scope (v1)

- `@FeignClient` interfaces.
- `@RestControllerAdvice` exception → response mapping.
- `MultipartFile` / file-upload bodies.
- Server-Sent Events / streaming response semantics.
- Multiple `produces` / `consumes` content types per endpoint (first one wins).
- Reading status codes from `ResponseEntity.status(...)` builder calls (always
  emits `200` in v1).
- Gradle plugin variant.

## Plugin coordinates

```
groupId:    community.flock.wirespec.bytecode
artifactId: wirespec-bytecode-extractor-maven-plugin
language:   Kotlin
```

## Configuration

```xml
<plugin>
  <groupId>community.flock.wirespec.bytecode</groupId>
  <artifactId>wirespec-bytecode-extractor-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <output>${project.build.directory}/wirespec</output>
    <basePackage>com.acme.api</basePackage> <!-- optional -->
  </configuration>
</plugin>
```

| Property      | Required | Description                                                  |
|---------------|----------|--------------------------------------------------------------|
| `output`      | yes      | Directory to write `.ws` files into.                         |
| `basePackage` | no       | If set, only scan classes whose FQN starts with this prefix. |

## Invocation

- **Goal:** `wirespec:extract` (full: `wirespec-bytecode-extractor:extract`).
- **Default phase binding:** `process-classes` (runs automatically right after
  `compile`).
- **Manual:** `mvn wirespec:extract`.

## Architecture

The plugin runs a 5-stage pipeline against a `URLClassLoader` built from the
project's runtime classpath plus `target/classes`:

```
target/classes
   │
   ▼
[1] ControllerScanner   → @RestController / @Controller+@ResponseBody classes
   │                      (framework packages auto-excluded; basePackage filter)
   ▼
[2] EndpointExtractor   → list<Endpoint>     (paths, methods, params, body)
   │
   ▼
[3] TypeExtractor       → set<WireType>      (recursive DTO walk, cycle cache)
   │
   ▼
[4] WirespecAstBuilder  → list<Definition>   (Wirespec compiler AST)
   │
   ▼
[5] Emitter             → .ws files          (one per controller + types.ws)
```

## Components

Each component is a single focused class with a small interface so it can be
unit-tested in isolation.

### `ExtractMojo`

Maven entry point.

```kotlin
@Mojo(
  name = "extract",
  defaultPhase = LifecyclePhase.PROCESS_CLASSES,
  requiresDependencyResolution = ResolutionScope.RUNTIME,
)
class ExtractMojo : AbstractMojo {
  @Parameter(required = true) lateinit var output: File
  @Parameter var basePackage: String? = null
  @Parameter(defaultValue = "\${project}", readonly = true) lateinit var project: MavenProject
}
```

Wires the pipeline and surfaces failures as `MojoExecutionException` with clear
messages.

### `ClasspathBuilder`

Given the `MavenProject`, returns a `URLClassLoader` over
`runtimeClasspathElements + target/classes`. Isolated from the plugin's own
classloader so reflection sees the user's deps without leakage.

### `ControllerScanner`

Uses [ClassGraph](https://github.com/classgraph/classgraph) to find classes
annotated with `@RestController`, or `@Controller` whose methods are annotated
with `@ResponseBody`. Filters:

- **Always exclude** packages: `org.springframework.*`, `org.springdoc.*`,
  `springfox.*`, `org.springdoc.api.*`, `io.swagger.*`.
- If `basePackage` is set, **also restrict** to classes starting with that prefix.

### `EndpointExtractor`

For one controller, produces `List<Endpoint>` (internal model). Responsibilities:

- Resolve class-level + method-level `@RequestMapping` (combined paths and
  inherited methods).
- Honor inherited `@RequestMapping` from superclasses and interfaces (Spring's
  behavior).
- Handle path/query/header/cookie params (`@PathVariable`, `@RequestParam`,
  `@RequestHeader`, `@CookieValue`) and request bodies (`@RequestBody`).
- Resolve response type by **unwrapping** wrapper types:
  - `ResponseEntity<T>`, `Mono<T>`, `Optional<T>`, `Callable<T>`,
    `DeferredResult<T>` → `T`
  - `Flux<T>` → `List<T>`
  - `void` / `Mono<Void>` → no body
- Determine status code from `@ResponseStatus` if present; otherwise `200`
  (`204` for void / empty `Mono`).
- Emit one Wirespec endpoint per HTTP method when a single
  `@RequestMapping(method = {GET, POST})` lists multiple.

### `TypeExtractor`

Given a `java.lang.reflect.Type`, returns a `WireType` (internal model).
Recursive with a memoizing cache to break cycles.

- **Field discovery:** record components, Kotlin data class properties, or
  JavaBean getter+field pairs.
- **Jackson:** honor `@JsonProperty` (rename), `@JsonIgnore` (skip).
- **Bean Validation:** honor `@NotNull`, `@Size`, `@Pattern`, `@Min`, `@Max`.
  `@NotNull`/`@NotBlank` participate in nullability resolution; `@Pattern`
  produces a Wirespec refined type where the regex is supported; `@Size` /
  `@Min` / `@Max` produce refined types where Wirespec supports the constraint
  and are otherwise dropped with a warning.
- **springdoc `@Schema`:** read `description` (attached as a Wirespec comment)
  and `required = true` (participates in nullability resolution).
- **Nullability resolution for DTO fields** (in priority order):
  1. Java primitives → non-null.
  2. `Optional<T>` → nullable, unwrapped to the inner `T` (never flattened into
     a synthetic `TOptional` object).
  3. Kotlin nullability metadata (via `kotlin.Metadata`) → as declared.
  4. `@Nullable` / `@NonNull` annotations → as declared. Recognised families:
     JSR-305 (`javax`/`jakarta`), JetBrains, Spring, Android, FindBugs,
     Checker Framework, and **JSpecify** (`org.jspecify.annotations`). Both
     declaration- and `TYPE_USE`-targeted annotations are read (JSpecify's are
     `TYPE_USE`-only, so they live on the annotated type, not the declaration).
  5. `@NotNull` / `@NotBlank` / `@Schema(required = true)` → non-null.
  6. JSpecify `@NullMarked` scope (method/field, enclosing class(es), or
     package; reversible with `@NullUnmarked`) → unannotated references default
     to non-null.
  7. Default → nullable.
- **Nullability resolution for binding parameters** (`@PathVariable`,
  `@RequestParam`, `@RequestHeader`, `@CookieValue`, `@RequestBody`): a binding
  parameter defaults to **non-null** (Spring resolves it as required). It
  becomes nullable when typed `Optional<T>` (unwrapped to nullable `T`),
  annotated `@Nullable`, declared with a Kotlin nullable type, or marked
  optional to Spring (`required = false` or a `defaultValue`). Java primitives
  are always non-null. An explicit `@Nullable` / `@NonNull` or Kotlin
  nullability wins over Spring's required flag.
- **Generics:** resolve bound type parameters via `ParameterizedType`. Unbound
  generics (`List<?>`) → `List<String>` + warning.
- **Unknown / un-resolvable types:** log warning, fall back to Wirespec `String`,
  do not fail the build.

### `WirespecAstBuilder`

Translates the internal `Endpoint` + `WireType` model to Wirespec compiler AST
nodes (`community.flock.wirespec.compiler.core.parse.Definition` and friends).
One small adapter per definition kind.

### `Emitter`

Wraps Wirespec's built-in emitter to render the AST.

- **Layout:** one `.ws` per controller (file name = simple class name) +
  one shared `types.ws` for all referenced DTOs.
- No import syntax is needed — Wirespec resolves type references globally by
  name across all `.ws` files in the compilation set, so endpoints in
  per-controller files automatically resolve types defined in `types.ws`.

## File writer behavior

- Output directory is treated as a **generated artifact**.
- Before each run, **delete all `*.ws` files** in `output` recursively.
- Never touch non-`.ws` files in `output`.
- Never write outside `output`.
- Fail with a clear message if `output` is not writable.

## Error handling

| Condition                              | Behavior                              |
|----------------------------------------|---------------------------------------|
| `output` not configured                | Fail (Maven validation).              |
| `output` not writable                  | Fail with clear message.              |
| `target/classes` missing or empty      | Fail with "did `compile` run?" hint.  |
| Single class fails to load             | Warn, skip that class, continue.      |
| Type can't be resolved                 | Warn, fall back to `String`, continue.|
| Unbound generic (`List<?>`)            | Warn, treat as `List<String>`.        |
| Wirespec emitter throws on a definition| Warn, skip definition, continue.      |

## Testing

- **Unit tests** per extractor against synthetic `Class` / `Method` / `Type`
  inputs, using small fixture classes compiled into the test sources.
- **Integration tests** with `maven-invoker-plugin`: a fixture Spring Boot
  module under `src/it/` covering: simple GET, POST with body, path/query/header
  params, nested DTOs, generics, `ResponseEntity`, `Mono`/`Flux`, validation
  annotations, `@JsonProperty`, enums, inheritance.
- **Snapshot tests:** emitted `.ws` files compared against committed
  `.expected.ws` fixtures.

## Project structure

```
wirespec-bytecode-extractor/
├── pom.xml
├── src/
│   ├── main/
│   │   └── kotlin/community/flock/wirespec/bytecode/extractor/
│   │       ├── ExtractMojo.kt
│   │       ├── ClasspathBuilder.kt
│   │       ├── ControllerScanner.kt
│   │       ├── EndpointExtractor.kt
│   │       ├── TypeExtractor.kt
│   │       ├── WirespecAstBuilder.kt
│   │       ├── Emitter.kt
│   │       └── model/                     (internal Endpoint, WireType, etc.)
│   ├── test/
│   │   └── kotlin/...                     (unit tests)
│   └── it/
│       └── basic-spring-app/              (invoker integration test)
│           ├── pom.xml
│           ├── invoker.properties
│           ├── verify.groovy
│           └── src/main/java/...
└── docs/superpowers/specs/
    └── 2026-05-12-wirespec-bytecode-extractor-design.md
```

## Open questions / future work

- **v2:** `@FeignClient` interfaces (consumer-side contracts).
- **v2:** `ResponseEntity.status(...)` reading via simple bytecode inspection.
- **v2:** Multipart / file-upload body modeling.
- **v2:** Gradle plugin variant.
