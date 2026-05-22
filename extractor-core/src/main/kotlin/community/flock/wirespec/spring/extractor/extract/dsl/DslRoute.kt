package community.flock.wirespec.spring.extractor.extract.dsl

import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod

/**
 * One route discovered in a Spring functional-DSL routing tree
 * (`router { GET("/x", h::x) }`, `coRouter`, `RouterFunctions.route()`).
 *
 * Carries enough information for the endpoint extractor to assemble a
 * Wirespec [community.flock.wirespec.spring.extractor.model.Endpoint] without
 * referring to ASM types.
 */
internal data class DslRoute(
    val method: HttpMethod,
    val path: String,
    /** Internal name of the class declaring the handler (Foo$Inner uses '/' separator). */
    val handlerOwnerInternal: String?,
    /** Handler method name (e.g. "getUser"). */
    val handlerName: String?,
    /** JVM descriptor of the handler (e.g. "(Lorg/springframework/web/reactive/function/server/ServerRequest;)Lreactor/core/publisher/Mono;"). */
    val handlerDescriptor: String?,
)
