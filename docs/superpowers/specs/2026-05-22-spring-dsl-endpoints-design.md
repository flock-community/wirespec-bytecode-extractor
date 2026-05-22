# Spring functional-DSL endpoint support — Design

**Date:** 2026-05-22
**Status:** Implemented in `extractor-core`

## Purpose

Extend the extractor to recognise Spring's *functional* HTTP-routing styles
in addition to annotated `@RestController`s:

- WebFlux Kotlin DSL — `router { GET("/x", h::x) }` and `coRouter { ... }`
- Spring MVC Kotlin DSL — `org.springframework.web.servlet.function.router { }`
- Java fluent builder — `RouterFunctions.route().GET("/x", h::x).build()`

## Approach

Static bytecode inspection — no Spring context is booted at extract time.

### Pipeline addition

```
                            (existing pipeline)
ControllerScanner ─────────────► EndpointExtractor ──┐
                                                     ├──► WirespecAstBuilder ──► Emitter
DslRouteScanner   ──► DslBytecodeWalker ──► DslEndpointExtractor ─┘
```

- **`DslRouteScanner`** — ClassGraph filter: classes declaring at least one
  method whose return type is `RouterFunction<...>` (WebFlux or MVC).
- **`DslBytecodeWalker`** — ASM tree walker over methods returning
  `RouterFunction`. Walks linearly, tracking the most recent path-string LDC
  and the most recent handler reference, consuming both at each DSL
  invocation. Recurses into nested lambda bodies (via INVOKEDYNAMIC
  `LambdaMetafactory` for Java SAMs, or generated `FunctionReferenceImpl`
  subclasses for Kotlin `h::method`) with the active path prefix extended.
- **`DslEndpointExtractor`** — turns each `DslRoute` into an `Endpoint`:
  resolves the handler `Method` reflectively; scans its bytecode for
  `ServerRequest.bodyToMono/bodyToFlux/body(Class)` calls to infer the
  request body; defers to the existing `ApiResponseExtractor` if the handler
  carries `@ApiResponses`/`@ApiResponse`, otherwise emits a single `200`
  response with no body.

### "Controller" mapping

Each DSL configuration class is treated as a virtual controller for output
purposes — its simple name becomes the `.ws` filename, and its routes share
the same `TypeOwnership` partitioning logic as annotated controllers.

## Limitations

- Only the `Class<T>` overloads of body extractors are read; the
  `ParameterizedTypeReference<T>` overloads fall back to no body.
- Response bodies are not derived from `ServerResponse.bodyValue(...)`
  builder chains. Users opt in to typed responses via
  `@ApiResponse(content = ...)` on the handler.
- Path-only predicates are honored (`path()`, `String.nest`). Other
  predicates (`accept(...)`, `contentType(...)`, `headers(...)`) are ignored
  for endpoint shape.
- Lambda-bodied handlers (`GET("/x") { req -> ... }`) get an entry with the
  right method+path but no body/response inference, since there is no
  reflective `Method` to inspect for annotations.

## Tests

- `DslBytecodeWalkerTest` — covers all three DSL flavors, nested prefixes,
  and handler reference resolution.
- `DslEndpointExtractorTest` — body inference across Kotlin/MVC/Java, default
  `200` response, controller-name carrying through.
- `WirespecExtractorTest.extract emits ws files for Spring functional DSL
  configuration classes` — end-to-end pipeline test.
