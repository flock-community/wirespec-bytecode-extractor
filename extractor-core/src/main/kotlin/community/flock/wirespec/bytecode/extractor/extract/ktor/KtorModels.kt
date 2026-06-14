package community.flock.wirespec.bytecode.extractor.extract.ktor

import community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod

/**
 * One route discovered in a Ktor **server** routing tree
 * (`routing { route("/users") { get { … } } }`).
 *
 * Carries enough information for [KtorEndpointExtractor] to assemble a Wirespec
 * endpoint without referring to ASM types. The handler is the generated suspend
 * lambda class that backs the `get { … }` block; its `invokeSuspend` body is
 * read to recover request/response bodies.
 */
internal data class KtorRoute(
    val method: HttpMethod,
    val path: String,
    /** Internal name of the generated suspend-lambda class that backs the handler block, or null. */
    val handlerClassInternal: String?,
)

/**
 * One HTTP call discovered in a Ktor **client** method
 * (`client.post("/users") { setBody(dto) }.body()`).
 *
 * Type references are captured as JVM internal names (recovered from the
 * `TypeInfo(KClass, KType)` construction the Kotlin compiler inlines at the
 * call site) and resolved to Wirespec types by [KtorClientExtractor].
 */
internal data class KtorClientCall(
    val method: HttpMethod,
    val path: String?,
    /** Raw/element internal names of the request body, from `setBody<T>` (null when absent). */
    val requestBodyRawInternal: String?,
    val requestBodyElementInternal: String?,
    /** Raw/element internal names of the response body, from `.body<T>()` (null when discarded). */
    val responseBodyRawInternal: String?,
    val responseBodyElementInternal: String?,
)
