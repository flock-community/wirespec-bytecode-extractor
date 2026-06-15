package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.WireType

/**
 * Splits a URL template into [PathSegment]s, shared by the Spring and JAX-RS
 * endpoint extractors. Both frameworks use the same `{name}` / `{name:regex}`
 * variable syntax.
 */
object PathParser {

    private val VARIABLE = Regex("""^\{\s*([^:}\s]+)\s*(?::[^}]+)?}$""")

    /**
     * Parse [path] into segments. A `{name}` (optionally `{name:regex}`) segment
     * becomes a [PathSegment.Variable]; its type is resolved from [pathParamTypes]
     * by variable name, defaulting to STRING when no binding parameter is found.
     */
    fun parse(
        path: String,
        pathParamTypes: Map<String, WireType> = emptyMap(),
    ): List<PathSegment> =
        path.split('/').filter { it.isNotBlank() }.map { seg ->
            val match = VARIABLE.matchEntire(seg)
            if (match != null) {
                val varName = match.groupValues[1]
                val type = pathParamTypes[varName] ?: WireType.Primitive(WireType.Primitive.Kind.STRING)
                PathSegment.Variable(varName, type)
            } else PathSegment.Literal(seg)
        }
}
