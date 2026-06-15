package community.flock.wirespec.extractor.extract.ktor

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.WireType
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Builds [Endpoint]s from [KtorRoute]s. The request body and responses are
 * recovered by reading the handler suspend-lambda's `invokeSuspend` bytecode:
 *
 *  - `call.receive<T>()` compiles to `ApplicationCall.receiveNullable(TypeInfo)`
 *    with `T` captured in the inlined `TypeInfo` → request body.
 *  - `call.respond(value)` / `call.respond(status, value)` compile to
 *    `ApplicationCall.respond(value, TypeInfo)`; the status (when present) comes
 *    from a preceding `HttpStatusCode.Companion.getXxx()`.
 *
 * Endpoints are named from their HTTP method and path (`GET /users/{id}` →
 * `GetUsersById`) since the handler lambda is anonymous.
 */
internal class KtorEndpointExtractor(
    types: TypeExtractor,
    private val classLoader: ClassLoader,
    private val onWarn: (String) -> Unit = {},
) {
    private val resolver = KtorTypeResolver(types, classLoader, onWarn)

    fun extract(configClass: Class<*>, routes: List<KtorRoute>): List<Endpoint> {
        if (routes.isEmpty()) return emptyList()
        val configSimple = configClass.simpleName
        val nameCounts = mutableMapOf<String, Int>()
        return routes.map { route -> buildEndpoint(configSimple, route, nameCounts) }
    }

    private fun buildEndpoint(
        configSimple: String,
        route: KtorRoute,
        nameCounts: MutableMap<String, Int>,
    ): Endpoint {
        val segments = parsePath(route.path)
        val handler = route.handlerClassInternal?.let { readHandler(it) }
        val requestBody = handler?.requestBody
        val responses = handler?.responses?.takeIf { it.isNotEmpty() } ?: listOf(Endpoint.Response(200, null))
        val baseName = endpointName(route, segments)
        val occurrence = nameCounts.merge(baseName, 1, Int::plus) ?: 1
        val finalName = if (occurrence == 1) baseName else "$baseName$occurrence"
        return Endpoint(
            controllerSimpleName = configSimple,
            name = finalName,
            method = route.method,
            pathSegments = segments,
            queryParams = handler?.params?.filter { it.source == Param.Source.QUERY } ?: emptyList(),
            headerParams = handler?.params?.filter { it.source == Param.Source.HEADER } ?: emptyList(),
            cookieParams = emptyList(),
            requestBody = requestBody,
            responses = responses,
        )
    }

    private fun endpointName(route: KtorRoute, segments: List<PathSegment>): String {
        val verb = route.method.name.lowercase().replaceFirstChar { it.uppercase() }
        val parts = segments.joinToString("") { seg ->
            when (seg) {
                is PathSegment.Literal -> seg.value.split('-', '_', '.')
                    .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
                is PathSegment.Variable -> "By" + seg.name.replaceFirstChar { it.uppercase() }
            }
        }
        return verb + parts
    }

    private fun parsePath(path: String): List<PathSegment> =
        path.split('/').filter { it.isNotBlank() }.map { seg ->
            // Ktor path params: {id}, {id?}, {id...}, {name?}
            val match = Regex("""^\{([^?.}]+)[?.]*}$""").matchEntire(seg)
            if (match != null) {
                PathSegment.Variable(match.groupValues[1], WireType.Primitive(WireType.Primitive.Kind.STRING))
            } else {
                PathSegment.Literal(seg)
            }
        }

    private data class HandlerInfo(
        val requestBody: WireType?,
        val responses: List<Endpoint.Response>,
        val params: List<Param>,
    )

    private fun readHandler(internalName: String): HandlerInfo? {
        val cn = KtorBytecode.readClass(classLoader, internalName) ?: return null
        val body = cn.methods.firstOrNull {
            it.name == "invokeSuspend" && it.desc == "(Ljava/lang/Object;)Ljava/lang/Object;"
        } ?: return null
        return scanHandlerBody(body)
    }

    private fun scanHandlerBody(method: MethodNode): HandlerInfo {
        val tracker = KtorBytecode.TypeInfoTracker()
        var requestBody: WireType? = null
        val responses = mutableListOf<Endpoint.Response>()
        var pendingStatus: Int? = null

        // Heuristic query/header recovery from `call.request.queryParameters["k"]`
        // / `headers["k"]` / `request.header("k")`. The lookup returns `String?`;
        // a following `Integer.parseInt`/`Boolean.parseBoolean`/… upgrades the type.
        val params = linkedMapOf<Pair<String, Param.Source>, Param>()
        var lastString: String? = null
        var lastParamSource: Param.Source? = null   // most recent queryParameters/headers/parameters accessor
        var pendingParamKey: Pair<String, Param.Source>? = null  // param awaiting a possible type conversion

        var i: AbstractInsnNode? = method.instructions?.first
        while (i != null) {
            tracker.onInsn(i)
            when (i) {
                is LdcInsnNode -> if (i.cst is String) lastString = i.cst as String

                is MethodInsnNode -> {
                    when {
                        i.owner == KtorBytecode.HTTP_STATUS_COMPANION ->
                            KtorBytecode.statusFromGetter(i.name)?.let { pendingStatus = it }

                        isReceiveCall(i) ->
                            requestBody = resolver.resolve(tracker.committedRaw, tracker.committedElement)

                        isRespondCall(i) -> {
                            val raw = tracker.committedRaw
                            val body =
                                if (raw == null || raw == "io/ktor/http/HttpStatusCode") null
                                else resolver.resolve(raw, tracker.committedElement)
                            responses += Endpoint.Response(pendingStatus ?: 200, body)
                            pendingStatus = null
                        }

                        // `request.queryParameters` / `headers` / `parameters` accessor —
                        // records the source for the next `get(key)` lookup.
                        i.name == "getQueryParameters" || i.name == "getRawQueryParameters" ->
                            lastParamSource = Param.Source.QUERY
                        i.name == "getHeaders" -> lastParamSource = Param.Source.HEADER
                        i.name == "getParameters" -> lastParamSource = Param.Source.PATH

                        // `Parameters.get(key)` / `Headers.get(key)` lookup.
                        isParamLookup(i) -> {
                            val source = if (i.owner == KtorBytecode.HEADERS) Param.Source.HEADER else lastParamSource
                            pendingParamKey = registerParam(params, lastString, source)
                            lastParamSource = null
                        }
                        // `request.header("k")` extension function → header param.
                        i.owner == KtorBytecode.REQUEST_PROPERTIES && (i.name == "header" || i.name == "headers") ->
                            pendingParamKey = registerParam(params, lastString, Param.Source.HEADER)

                        // A `String.toInt()/toBoolean()/…` conversion applied to the value
                        // just looked up — upgrade that param's primitive type.
                        else -> KtorBytecode.conversionKind(i.owner, i.name)?.let { kind ->
                            pendingParamKey?.let { key ->
                                params[key] = params.getValue(key).copy(
                                    type = WireType.Primitive(kind, nullable = true),
                                )
                                pendingParamKey = null
                            }
                        }
                    }
                }
            }
            i = i.next
        }
        return HandlerInfo(requestBody, responses.distinct(), params.values.toList())
    }

    /** Records a query/header param (default `String?`), returning its key, or null when not applicable. */
    private fun registerParam(
        params: MutableMap<Pair<String, Param.Source>, Param>,
        key: String?,
        source: Param.Source?,
    ): Pair<String, Param.Source>? {
        // Path params are already recovered from the route string; ignore them here.
        if (key == null || source == null || source == Param.Source.PATH) return null
        val mapKey = key to source
        params.getOrPut(mapKey) {
            Param(key, source, WireType.Primitive(WireType.Primitive.Kind.STRING, nullable = true))
        }
        return mapKey
    }

    private fun isParamLookup(insn: MethodInsnNode): Boolean =
        (insn.owner == KtorBytecode.PARAMETERS || insn.owner == KtorBytecode.HEADERS ||
            insn.owner == KtorBytecode.STRING_VALUES) &&
            insn.name == "get" &&
            insn.desc == "(Ljava/lang/String;)Ljava/lang/String;"

    private fun isReceiveCall(insn: MethodInsnNode): Boolean =
        (insn.name == "receive" || insn.name == "receiveNullable") &&
            insn.desc.contains("Lio/ktor/util/reflect/TypeInfo;")

    private fun isRespondCall(insn: MethodInsnNode): Boolean =
        (insn.name == "respond" || insn.name == "respondNullable") &&
            insn.desc.contains("Lio/ktor/util/reflect/TypeInfo;")
}
