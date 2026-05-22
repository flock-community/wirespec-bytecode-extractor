# wirespec-spring-extractor

[![Maven plugin (Maven Central)](https://img.shields.io/maven-central/v/community.flock.wirespec.spring/wirespec-spring-extractor-maven-plugin?label=maven-plugin)](https://central.sonatype.com/artifact/community.flock.wirespec.spring/wirespec-spring-extractor-maven-plugin)
[![Gradle plugin (Maven Central)](https://img.shields.io/maven-central/v/community.flock.wirespec.spring.extractor/community.flock.wirespec.spring.extractor.gradle.plugin?label=gradle-plugin)](https://central.sonatype.com/artifact/community.flock.wirespec.spring.extractor/community.flock.wirespec.spring.extractor.gradle.plugin)

A Maven and Gradle plugin that scans a Spring Boot application's compiled classes and emits
[Wirespec](https://wirespec.io) (`.ws`) files describing its HTTP endpoints and DTO types.

## Usage (Maven)

Drop the plugin into `pom.xml` with `<extensions>true</extensions>` and it
auto-binds to `process-classes`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>community.flock.wirespec.spring</groupId>
      <artifactId>wirespec-spring-extractor-maven-plugin</artifactId>
      <version>0.0.3</version>
      <extensions>true</extensions>
      <configuration>
        <!-- optional — defaults to ${project.build.directory}/wirespec -->
        <output>${project.build.directory}/wirespec</output>
        <!-- optional — only scan classes under this package -->
        <basePackage>com.acme.api</basePackage>
      </configuration>
    </plugin>
  </plugins>
</build>
```

`mvn package` (or any goal from `process-classes` onward) writes `.ws`
files into `target/wirespec/`. To trigger the goal directly:

```bash
mvn wirespec:extract
```

## Usage (Gradle)

> **Plugin resolution.** The Gradle plugin is published to **Maven Central**,
> not the Gradle Plugin Portal. Gradle's `plugins { id(...) }` block only checks
> the Plugin Portal by default, so you must add `mavenCentral()` to plugin
> resolution. Put this at the **top** of `settings.gradle.kts`, **before** any
> `include(...)` or `dependencyResolutionManagement {}` block:
>
> ```kotlin
> pluginManagement {
>     repositories {
>         mavenCentral()
>         gradlePluginPortal()
>     }
> }
> ```
>
> Without this snippet the build fails with
> `Plugin [id: 'community.flock.wirespec.spring.extractor', version: '...'] was not found in any of the following sources`.

### Kotlin project

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "0.0.3"
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // So Spring's @PathVariable/@RequestParam (and the extractor) can
        // recover parameter names that have no explicit value().
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespecExtractor {
    // optional — defaults to build/wirespec
    // outputDir.set(layout.buildDirectory.dir("wirespec"))

    // optional — only scan classes under this package
    basePackage.set("com.acme.api")
}
```

### Java project

```kotlin
plugins {
    java
    id("community.flock.wirespec.spring.extractor") version "0.0.3"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

// So Spring's @PathVariable/@RequestParam (and the extractor) can recover
// parameter names that have no explicit value().
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
```

Applying the plugin alongside any JVM source plugin auto-wires
`extractWirespec` into `assemble`, so `gradle build` (or `gradle assemble`)
writes `.ws` files into `build/wirespec/`. To trigger it directly:

```bash
./gradlew extractWirespec
```

> ⚠️ **Compile with `-parameters` / `-java-parameters`.** Without it the
> JVM erases method parameter names, and `@PathVariable Long id` —
> declared without an explicit `value` — comes out as `arg0`. Both
> fixture builds in this repo set the flag, and you should too.

## What it extracts

- `@RestController` / `@Controller` (with `@ResponseBody`) classes
- The `@RequestMapping` family (`@GetMapping`, `@PostMapping`, etc.)
- Path / query / header / cookie parameters; `@RequestBody`
- Response types, including unwrapping of `ResponseEntity`, `Mono`, `Flux`,
  `Optional`, `Callable`, `DeferredResult`
- Multiple response statuses via springdoc `@ApiResponses` /
  `@ApiResponse` — one Wirespec response per declared status. See
  [Multiple responses](#multiple-responses).
- DTO classes referenced by endpoints, with Jackson (`@JsonProperty`,
  `@JsonIgnore`), Bean Validation (`@NotNull`, `@Size`, `@Pattern`, `@Min`,
  `@Max`), and springdoc `@Schema` awareness
- Spring functional-DSL routes — WebFlux Kotlin `router { }` / `coRouter { }`,
  Spring MVC Kotlin `router { }`, and the Java fluent
  `RouterFunctions.route()` builder. See
  [Functional DSL routes](#functional-dsl-routes).
- Spring Kafka listeners and producers — emitted as Wirespec
  `channel` definitions. See [Kafka extraction](#kafka-extraction).

### Generic types

Wirespec has no concept of generic type parameters. The extractor flattens
every concrete generic instantiation it encounters into its own named
wirespec type with the type arguments substituted:

| Java/Kotlin                  | Wirespec                                       |
| ---------------------------- | ---------------------------------------------- |
| `Page<UserDto>`              | `type UserDtoPage`                             |
| `Wrapper<Int>`               | `type IntegerWrapper`                          |
| `Pair<UserDto, OrderDto>`    | `type UserDtoOrderDtoPair`                     |
| `Page<Wrapper<UserDto>>`     | `type UserDtoWrapper`, `type UserDtoWrapperPage` |
| `ApiResponse<List<UserDto>>` | `type UserDtoListApiResponse`                  |

Names are composed by reading type arguments innermost-first then the generic
class's simple name. `List` and `Map` are wirespec-native containers: they
stay as `T[]` and `{T}` at use sites and only contribute a `List` / `Map`
suffix when they appear inside another generic's type arguments.

The extractor fails the build (with a pointer to the offending controller
method) when it encounters:

- a raw generic at a reference site (`fun list(): Page` — no type argument),
- a wildcard argument (`Page<*>`, `Page<?>`),
- a class extending a generic parent without arguments
  (`class UserPage : Page`).

This monomorphization rule means controller signatures must always bind
their generic parameters concretely.

### Multiple responses

Spring/springdoc lets a handler declare multiple response variants. The
extractor reads `io.swagger.v3.oas.annotations.responses.ApiResponses` (and
standalone `@ApiResponse`) and emits one Wirespec `Response` per entry:

```kotlin
@GetMapping("/users/{id}")
@ApiResponses(
    ApiResponse(responseCode = "200"),
    ApiResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = ErrorDto::class))],
    ),
)
fun getUser(@PathVariable id: String): UserDto = ...
```

Behavior:

- One Wirespec response per `@ApiResponse`. Status comes from `responseCode`.
- Body comes from `content[].schema.implementation` (or
  `content[].array.schema.implementation` → list).
- An `@ApiResponse` without a `content` schema falls back to the method's
  return type **only** when its status matches the natural success status
  (e.g. `200` for value-returning, `204` for `void`); otherwise it is
  emitted body-less.
- Non-numeric `responseCode`s (`"default"`, `"2XX"`) are skipped with a
  warning.
- Without any `@ApiResponse`(s), behavior is unchanged: one response derived
  from the method signature plus `@ResponseStatus`.

### Functional DSL routes

Any class with at least one method returning
`org.springframework.web.reactive.function.server.RouterFunction` (WebFlux) or
`org.springframework.web.servlet.function.RouterFunction` (Spring MVC) is
treated as a virtual controller. Its simple class name becomes the
`<Name>.ws` file. Routes are discovered by **static bytecode inspection** —
no Spring context is booted, and no DSL code is executed at extract time.

Recognised:

- HTTP-method calls: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`.
- Nesting prefixes via `String.nest { }` / `RequestPredicate.nest { }`
  (Kotlin) and `Builder.nest(predicate, c)` / `Builder.path(prefix, c)` (Java).
- Handler references — `handler::method` (Kotlin, compiled to a
  `FunctionReferenceImpl` subclass) and `handler::method` (Java, compiled to
  an `INVOKEDYNAMIC LambdaMetafactory`).
- Request body inference: scans the resolved handler's bytecode for
  `ServerRequest.bodyToMono(Class)` / `bodyToFlux(Class)` / `body(Class)` and
  uses the captured class literal as the Wirespec request body.

Responses default to a single `200` with no body. To declare typed responses,
annotate the handler method with springdoc `@ApiResponses` / `@ApiResponse` —
those are read by the same code path as annotated controllers.

```kotlin
class RouterConfig {
    fun routes(h: UserHandler): RouterFunction<ServerResponse> = router {
        "/users".nest {
            GET("", h::list)
            POST("", h::create)
            "/{id}".nest {
                GET("", h::getOne)
                DELETE("", h::delete)
            }
        }
    }
}
```

**Limitations:**

- Request bodies are only detected for `Class<T>` overloads of
  `bodyToMono` / `bodyToFlux` / `body`. The
  `ParameterizedTypeReference<T>` overloads — and bodies passed via
  `BodyExtractors` — fall back to no body.
- Response bodies can't be inferred from `ServerResponse.bodyValue(...)`
  builder chains; use `@ApiResponse(content = ...)` on the handler to declare
  them.
- Lambda bodies in DSL handler positions (`GET("/x") { req -> ... }`) are
  recognised but only their *paths* and HTTP methods are extracted — the
  lambda has no `Method` to inspect for `@ApiResponse` or
  `request.bodyToMono` calls.
- Predicates other than `path()` / `nest()` (`accept(...)`, `contentType(...)`,
  `headers(...)`) are ignored for path purposes.

### Kafka extraction

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

**Out of scope (v1):** `@SendTo` on listener return values; producers
passing `ProducerRecord<K, V>` or `Message<?>` to `send(...)`; keys,
headers, consumer groups, and topic-to-channel name resolution.

### Known limitations (v1)

- `@Controller` classes with handler methods directly annotated with
  `@ResponseBody`. Meta-annotated variants (e.g., custom annotations that are
  themselves annotated with `@ResponseBody`) are not detected in v1.

See [docs/superpowers/specs/2026-05-12-wirespec-spring-extractor-design.md](docs/superpowers/specs/2026-05-12-wirespec-spring-extractor-design.md)
for the full design and current scope limitations.

## Output layout

- One `<ControllerName>.ws` per controller — endpoints, plus DTO/enum/refined
  types referenced only by that controller.
- One shared `types.ws` — DTO/enum/refined types referenced by two or more
  controllers. Omitted when all types are controller-local.
- The output directory is treated as a generated artifact: existing `*.ws`
  files are deleted on each run; non-`.ws` files are left alone.
