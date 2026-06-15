package community.flock.wirespec.extractor.extract.ktor

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.WireType

/**
 * Builds [Endpoint]s from Ktor **client** calls discovered by [KtorClientWalker].
 *
 * Each call's request/response body types — captured as JVM internal names from
 * the inlined `TypeInfo` — are resolved via the shared [TypeExtractor]. Endpoints
 * are grouped under the declaring class's simple name (one `<Client>.ws` file)
 * and named after the calling method (`fun listUsers()` → `ListUsers`); a method
 * issuing several requests gets numeric suffixes.
 */
internal class KtorClientExtractor(
    types: TypeExtractor,
    classLoader: ClassLoader,
    private val onWarn: (String) -> Unit = {},
) {
    private val resolver = KtorTypeResolver(types, classLoader, onWarn)

    fun extract(clientClass: Class<*>): List<Endpoint> {
        val callsByMethod = KtorClientWalker.walk(clientClass)
        if (callsByMethod.isEmpty()) return emptyList()
        val configSimple = clientClass.simpleName
        return callsByMethod.flatMap { (methodName, calls) ->
            calls.mapIndexed { index, call ->
                val base = methodName.replaceFirstChar { it.uppercase() }
                val name = if (calls.size == 1) base else "$base${index + 1}"
                buildEndpoint(configSimple, name, call)
            }
        }
    }

    private fun buildEndpoint(configSimple: String, name: String, call: KtorClientCall): Endpoint {
        val requestBody = call.requestBodyRawInternal?.let {
            resolver.resolve(it, call.requestBodyElementInternal)
        }
        val responseBody = call.responseBodyRawInternal?.let {
            resolver.resolve(it, call.responseBodyElementInternal)
        }
        return Endpoint(
            controllerSimpleName = configSimple,
            name = name,
            method = call.method,
            pathSegments = parsePath(call.path),
            queryParams = emptyList(),
            headerParams = emptyList(),
            cookieParams = emptyList(),
            requestBody = requestBody,
            responses = listOf(Endpoint.Response(200, responseBody)),
        )
    }

    private fun parsePath(path: String?): List<PathSegment> =
        path.orEmpty().split('/').filter { it.isNotBlank() }.map { seg ->
            val match = Regex("""^\{([^?.}]+)[?.]*}$""").matchEntire(seg)
            if (match != null) {
                PathSegment.Variable(match.groupValues[1], WireType.Primitive(WireType.Primitive.Kind.STRING))
            } else {
                PathSegment.Literal(seg)
            }
        }
}
