# Multiple response statuses and types

## Goal

Spring/springdoc lets a single handler declare multiple response variants
(`200 → UserDto`, `404 → ErrorDto`, …). The current extractor models exactly
one response per endpoint. This change lets a single endpoint emit one
Wirespec `Response` per declared status.

## Scope

**In:**
- springdoc / OpenAPI annotations:
  - `@io.swagger.v3.oas.annotations.responses.ApiResponses`
  - `@io.swagger.v3.oas.annotations.responses.ApiResponse` (one or more, either inside `@ApiResponses` or repeated directly)
- Per-response body schema from `@ApiResponse.content[].schema.implementation`, including the list form `@ApiResponse.content[].array.schema.implementation`.

**Out (deferred):**
- `@ApiResponse` references by name (`ref =`) — not used.
- `oneOf` / `anyOf` schemas — Wirespec models a single body per response.
- `throws SomeException` where the exception carries `@ResponseStatus`.
- Sealed/union return types.
- `produces = "application/xml"` — content type stays `application/json` to match the existing single-response code path.

## Behavior

1. If a handler method has **no** `@ApiResponse` / `@ApiResponses`, behavior is
   unchanged: one response = `(method's @ResponseStatus or 200/204, method return type)`.
2. If `@ApiResponses` (or one or more standalone `@ApiResponse`) is present:
   - Each `@ApiResponse` becomes one Wirespec response.
   - Status: `Integer.parseInt(responseCode)`. Non-numeric codes (`"default"`,
     `"2XX"`) are skipped with a warning.
   - Body: first `@Content` entry's `schema.implementation` (or
     `array.schema.implementation` → wrapped in `ListOf`). If both
     `schema.implementation` and `array.schema.implementation` are `Void.class`
     (the swagger default), treat as "no schema declared".
   - **Fallback when no schema declared**:
     - If this response's status equals the method's natural success status
       (the value `statusCodeFor` would return today) → use the method return
       type.
     - Otherwise → body-less response (status only).

## Model change

```kotlin
data class Endpoint(
    val controllerSimpleName: String,
    val name: String,
    val method: HttpMethod,
    val pathSegments: List<PathSegment>,
    val queryParams: List<Param>,
    val headerParams: List<Param>,
    val cookieParams: List<Param>,
    val requestBody: WireType? = null,
    val responses: List<Response>,   // replaces (responseBody, statusCode); never empty
) {
    data class Response(val statusCode: Int, val body: WireType?)
}
```

This is an internal model — no compatibility shims.

## AST emission

`WirespecAstBuilder.toEndpoint` builds one `WsEndpoint.Response` per entry of
`Endpoint.responses`. Body-less responses get `content = null`; bodied responses
get `WsEndpoint.Content("application/json", toReference(body))`.

## File touchpoints

- `model/Endpoint.kt` — new `Response` nested class; field rename.
- `extract/ReturnTypeUnwrapper.kt` — unchanged (`statusCodeFor` keeps the
  "natural status" notion).
- `extract/ApiResponseExtractor.kt` (new) — reads `@ApiResponse(s)` and yields
  `List<Endpoint.Response>`.
- `extract/EndpointExtractor.kt` — calls `ApiResponseExtractor` and falls back
  to the legacy single-response path.
- `ast/WirespecAstBuilder.kt` — emits one `WsEndpoint.Response` per entry.
- Tests + a `MultiResponseController` fixture.

## Test plan

Unit:
- `@ApiResponses` with two `@ApiResponse` entries → two responses, correct
  status + body each.
- `@ApiResponse` without `content` on a non-success status → body-less.
- `@ApiResponse` without `content` on the success status → falls back to
  method return type.
- `array.schema.implementation` → `WireType.ListOf`.
- Non-numeric `responseCode` is dropped (with warning).
- No annotations → single response, identical to current output.

Emitter:
- Multi-response endpoint emits multiple `=> 200, => 404, …` clauses in the
  generated `.ws`.
