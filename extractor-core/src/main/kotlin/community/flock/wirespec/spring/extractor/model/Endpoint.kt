// src/main/kotlin/community/flock/wirespec/spring/extractor/model/Endpoint.kt
package community.flock.wirespec.spring.extractor.model

data class Endpoint(
    val controllerSimpleName: String,
    val name: String,                     // Wirespec definition name (PascalCase)
    val method: HttpMethod,
    val pathSegments: List<PathSegment>,
    val queryParams: List<Param>,
    val headerParams: List<Param>,
    val cookieParams: List<Param>,
    val requestBody: WireType? = null,
    val responses: List<Response>,        // never empty
) {
    enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD, TRACE }

    sealed interface PathSegment {
        data class Literal(val value: String) : PathSegment
        data class Variable(val name: String, val type: WireType) : PathSegment
    }

    /** A single declared response variant for an endpoint. */
    data class Response(val statusCode: Int, val body: WireType?)
}
