# wirespec-bytecode-extractor

[![Maven plugin (Maven Central)](https://img.shields.io/maven-central/v/community.flock.wirespec.bytecode/wirespec-bytecode-extractor-maven-plugin?label=maven-plugin)](https://central.sonatype.com/artifact/community.flock.wirespec.bytecode/wirespec-bytecode-extractor-maven-plugin)
[![Gradle plugin (Maven Central)](https://img.shields.io/maven-central/v/community.flock.wirespec.bytecode.extractor/community.flock.wirespec.bytecode.extractor.gradle.plugin?label=gradle-plugin)](https://central.sonatype.com/artifact/community.flock.wirespec.bytecode.extractor/community.flock.wirespec.bytecode.extractor.gradle.plugin)

A Maven and Gradle plugin that scans a Spring Boot application's compiled classes and emits
[Wirespec](https://wirespec.io) (`.ws`) files describing its HTTP endpoints and DTO types.

## Usage (Maven)

Drop the plugin into `pom.xml` with `<extensions>true</extensions>` and it
auto-binds to `process-classes`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>community.flock.wirespec.bytecode</groupId>
      <artifactId>wirespec-bytecode-extractor-maven-plugin</artifactId>
      <version>0.0.3</version>
      <extensions>true</extensions>
      <configuration>
        <!-- optional — defaults to ${project.build.directory}/wirespec -->
        <output>${project.build.directory}/wirespec</output>
        <!-- optional — only scan classes under this package -->
        <basePackage>com.acme.api</basePackage>
        <!-- optional — all default to true. Disable a path to extract only the others. -->
        <extractSpring>true</extractSpring>
        <extractOpenApi>true</extractOpenApi>
        <extractKtor>true</extractKtor>
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
> `Plugin [id: 'community.flock.wirespec.bytecode.extractor', version: '...'] was not found in any of the following sources`.

### Kotlin project

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.bytecode.extractor") version "0.0.3"
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

    // optional — all default to true. Disable a path to extract only the others.
    // extractSpring.set(true)    // Spring MVC controllers, DSL routes, messaging
    // extractOpenApi.set(true)   // JAX-RS resources + swagger annotations
    // extractKtor.set(true)      // Ktor server routing + client calls
}
```

### Java project

```kotlin
plugins {
    java
    id("community.flock.wirespec.bytecode.extractor") version "0.0.3"
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

    // optional — both default to true
    // extractSpring.set(true)
    // extractOpenApi.set(true)
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
- Nullability for both DTO fields and endpoint parameters — from Kotlin types,
  `Optional<T>`, Spring's `required` flag, and `@Nullable` / `@NonNull`
  annotations (JSR-305, JetBrains, Spring, JSpecify — including JSpecify's
  `@NullMarked` scopes). See [Nullability](#nullability).
- Spring functional-DSL routes — WebFlux Kotlin `router { }` / `coRouter { }`,
  Spring MVC Kotlin `router { }`, and the Java fluent
  `RouterFunctions.route()` builder. See
  [Functional DSL routes](#functional-dsl-routes).
- JAX-RS resources — classes routed with `@Path` / `@GET` / `@POST` / … where
  the OpenAPI detail (parameters, request body, responses, schemas, operation
  names) is driven entirely by swagger/OpenAPI annotations. See
  [JAX-RS resources](#jax-rs-resources-swagger-driven).
- Spring Kafka listeners and producers — emitted as Wirespec
  `channel` definitions. See [Kafka extraction](#kafka-extraction).
- Ktor server routing trees (`routing { route("/users") { get { … } } }`) and
  Ktor client request calls (`client.post("/users") { setBody(dto) }.body()`).
  See [Ktor extraction](#ktor-extraction-server--client).

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

### Nullability

Every emitted field and parameter is marked nullable (`T?`) or non-null (`T`).

**DTO fields** default to nullable, and are resolved in priority order:

1. Java primitives → non-null.
2. `Optional<T>` → nullable, unwrapped to the inner `T`.
3. Kotlin nullability (`String?` vs `String`) via `@Metadata`.
4. `@Nullable` / `@NonNull` annotations — JSR-305 (`javax`/`jakarta`), JetBrains,
   Spring, Android, FindBugs, Checker Framework, and **JSpecify**. Both
   declaration- and `TYPE_USE`-targeted annotations are read (JSpecify's are
   `TYPE_USE`-only).
5. `@NotNull` / `@NotBlank` / `@Schema(required = true)` → non-null.
6. JSpecify `@NullMarked` in scope (the field/method, an enclosing class, or the
   package — reversible with `@NullUnmarked`) → unannotated references become
   non-null.

**Endpoint parameters** (`@PathVariable`, `@RequestParam`, `@RequestHeader`,
`@CookieValue`, `@RequestBody`) instead default to **non-null** — Spring treats
them as required. A parameter becomes nullable when it is typed `Optional<T>`,
annotated `@Nullable`, declared with a Kotlin nullable type, or marked optional
to Spring (`required = false` or a `defaultValue`). An explicit annotation or a
Kotlin nullable type wins over the `required` flag; Java primitives are always
non-null.

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

### JAX-RS resources (swagger-driven)

Alongside Spring controllers, the extractor discovers **JAX-RS root resources** —
classes annotated with `@Path` (either the `jakarta.ws.rs` or the legacy
`javax.ws.rs` namespace). This is the classic non-Spring OpenAPI stack
(Jersey / RESTEasy / Quarkus).

Routing is read from JAX-RS annotations; **everything else is driven by
swagger/OpenAPI annotations**:

- **Path + HTTP method** come from class- and method-level `@Path` plus the
  `@GET` / `@POST` / `@PUT` / `@PATCH` / `@DELETE` / `@HEAD` / `@OPTIONS`
  annotations (discovered via the JAX-RS `@HttpMethod` meta-annotation, so
  custom HTTP-method annotations work too). Swagger annotations carry no URL
  path, which is why routing must come from JAX-RS.
- **Parameters** come from `@PathParam` / `@QueryParam` / `@HeaderParam` /
  `@CookieParam`, or from a swagger `@Parameter(in = …)` when no JAX-RS binding
  annotation is present. Path params default to required (non-null); query,
  header, and cookie params default to optional (nullable) unless
  `@Parameter(required = true)`, a `@NotNull`, or a non-null Kotlin type says
  otherwise.
- **Request body** is the swagger `@RequestBody`-annotated parameter (its
  `@Content` schema wins when declared), or the lone JAX-RS entity parameter —
  the method argument with no binding/injected (`@Context`, `@BeanParam`, …)
  annotation.
- **Responses** are read by the same code path as annotated Spring controllers:
  swagger `@ApiResponses` / `@ApiResponse` produce one Wirespec response per
  declared status, falling back to the method signature otherwise. See
  [Multiple responses](#multiple-responses).
- **Operation name** is the swagger `@Operation(operationId = …)` when present
  (PascalCased), else the method name. `@Operation(hidden = true)` methods are
  skipped.

```kotlin
@Path("/users")
class UserResource {
    @GET
    @Operation(operationId = "listUsers")
    fun list(@QueryParam("active") active: Boolean?): List<UserDto> = …

    @GET
    @Path("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(
            responseCode = "404",
            content = [Content(schema = Schema(implementation = ErrorDto::class))],
        ),
    )
    fun getOne(@PathParam("id") id: String): UserDto = …
}
```

JAX-RS annotations are read reflectively by fully-qualified name, so — like the
messaging scanners — the extractor needs no JAX-RS API on its own classpath and
cleanly no-ops on projects that don't use JAX-RS. Resources are emitted to a
`<ResourceName>.ws` file, the same convention used for controllers.

The Spring and JAX-RS/OpenAPI paths run independently and can be toggled via
`extractSpring` / `extractOpenApi` (both default `true`) — set one to `false` to
extract only the other. See the [Maven](#usage-maven) and [Gradle](#usage-gradle)
configuration above.

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

### Ktor extraction (server + client)

Alongside the Spring/JAX-RS paths, the extractor discovers **Ktor** endpoints —
both the server routing tree and the client request DSL. Like the Spring
functional DSL, routes are found by **static bytecode inspection**: no Ktor
application is booted and no DSL code runs at extract time. Ktor is read entirely
by fully-qualified name, so it needs no Ktor API on the extractor's classpath and
cleanly no-ops on projects that don't use Ktor.

**Server.** Any class whose bytecode builds a routing tree
(`io.ktor.server.routing` — `routing { }`, `route(...)`, `get`/`post`/…) is
treated as a virtual controller; its simple class name becomes the `<Name>.ws`
file (a top-level `fun Application.module()` lands in its `…Kt` file facade).

- HTTP-method builders: `get`, `post`, `put`, `patch`, `delete`, `head`,
  `options`, with the path taken from `route("/prefix") { }` nesting and/or an
  inline `get("/path") { }` argument. Ktor path parameters (`{id}`, `{id?}`,
  `{id...}`) become Wirespec path variables.
- Request body: recovered from `call.receive<T>()`.
- Responses: `call.respond(value)` yields a `200` with the value's type;
  `call.respond(HttpStatusCode.Created, value)` and `call.respond(status)` use
  the declared status (`201`, `204`, …). Multiple `respond` calls in one handler
  become multiple responses.
- Endpoints are named from method + path (`GET /users/{id}` → `GetUsersById`)
  since the handler lambda is anonymous.

```kotlin
fun Application.userRoutes() {
    routing {
        route("/users") {
            get { call.respond(userService.all()) }
            post {
                val dto = call.receive<UserDto>()
                call.respond(HttpStatusCode.Created, userService.create(dto))
            }
            get("/{id}") { call.respond(userService.find(call.parameters["id"]!!)) }
        }
    }
}
```

**Client.** Any class that issues `HttpClient` requests is emitted to a
`<Client>.ws` file, with one endpoint per calling method
(`suspend fun listUsers()` → `ListUsers`).

- HTTP verb from `client.get/post/put/patch/delete/head/options(...)`.
- URL from the request's literal path string.
- Request body from `setBody(value)`; response body from `.body<T>()`.

```kotlin
class UserClient(private val client: HttpClient) {
    suspend fun listUsers(): List<UserDto> = client.get("/users").body()
    suspend fun createUser(dto: UserDto): UserDto =
        client.post("/users") { setBody(dto) }.body()
}
```

**Limitations (v1):**

- Routing config lambdas are followed when the Kotlin compiler emits them as
  `invokedynamic` (the default since Kotlin 2.0); handler bodies in DSL position
  (`get { … }`) are read as generated suspend-lambda classes.
- Query / header / cookie parameters (`call.request.queryParameters[...]`) are
  not extracted — only path, method, request body, and responses.
- Response/request bodies are recovered from the reified `receive` / `respond` /
  `setBody` / `body` type arguments; bodies passed via non-reified overloads are
  not read.
- Client URLs are read from the literal path string. Interpolated paths
  (`"/users/$id"`) are only partially recovered (the static portion), so dynamic
  segments may be missing.

### Known limitations (v1)

- `@Controller` classes with handler methods directly annotated with
  `@ResponseBody`. Meta-annotated variants (e.g., custom annotations that are
  themselves annotated with `@ResponseBody`) are not detected in v1.

See [docs/superpowers/specs/2026-05-12-wirespec-bytecode-extractor-design.md](docs/superpowers/specs/2026-05-12-wirespec-bytecode-extractor-design.md)
for the full design and current scope limitations.

## Output layout

- One `<ControllerName>.ws` per controller — endpoints, plus DTO/enum/refined
  types referenced only by that controller.
- One shared `types.ws` — DTO/enum/refined types referenced by two or more
  controllers. Omitted when all types are controller-local.
- The output directory is treated as a generated artifact: existing `*.ws`
  files are deleted on each run; non-`.ws` files are left alone.
