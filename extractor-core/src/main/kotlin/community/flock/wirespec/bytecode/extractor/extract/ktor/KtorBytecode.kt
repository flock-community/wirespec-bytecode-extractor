package community.flock.wirespec.bytecode.extractor.extract.ktor

import community.flock.wirespec.bytecode.extractor.model.WireType.Primitive.Kind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Shared bytecode constants and helpers for the Ktor server/client walkers.
 *
 * Everything Ktor-specific is referenced by JVM internal name (string), never by
 * a compile-time type — extractor-core does not depend on Ktor. This is the same
 * "read annotations/types by FQN" approach used by the JAX-RS and messaging
 * scanners, so extraction cleanly no-ops on projects that don't use Ktor.
 */
internal object KtorBytecode {

    // --- server routing -----------------------------------------------------
    const val ROUTING_BUILDER = "io/ktor/server/routing/RoutingBuilderKt"
    const val ROUTING_ROOT = "io/ktor/server/routing/RoutingRootKt"

    /** HTTP-verb builder functions on `Route` (in [ROUTING_BUILDER]). */
    val SERVER_HTTP_METHODS = mapOf(
        "get" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.GET,
        "post" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.POST,
        "put" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.PUT,
        "patch" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.PATCH,
        "delete" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.DELETE,
        "head" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.HEAD,
        "options" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.OPTIONS,
    )

    // --- request / response payloads ---------------------------------------
    const val TYPE_INFO = "io/ktor/util/reflect/TypeInfo"
    const val HTTP_STATUS_COMPANION = "io/ktor/http/HttpStatusCode\$Companion"
    const val HTTP_METHOD_COMPANION = "io/ktor/http/HttpMethod\$Companion"

    // --- query / header parameters (server) --------------------------------
    const val PARAMETERS = "io/ktor/http/Parameters"
    const val HEADERS = "io/ktor/http/Headers"
    const val STRING_VALUES = "io/ktor/util/StringValues"
    const val REQUEST_PROPERTIES = "io/ktor/server/request/ApplicationRequestPropertiesKt"

    /**
     * Maps a `String.toXxx()` conversion call (by owner+name) that the handler
     * applies to a looked-up query/header value to the Wirespec primitive kind it
     * implies. Absence means the value stays a `String`.
     */
    fun conversionKind(owner: String, name: String): Kind? = CONVERSIONS[owner to name]

    private val CONVERSIONS: Map<Pair<String, String>, Kind> = mapOf(
        ("java/lang/Boolean" to "parseBoolean") to Kind.BOOLEAN,
        ("java/lang/Integer" to "parseInt") to Kind.INTEGER_32,
        ("java/lang/Long" to "parseLong") to Kind.INTEGER_64,
        ("java/lang/Double" to "parseDouble") to Kind.NUMBER_64,
        ("java/lang/Float" to "parseFloat") to Kind.NUMBER_32,
        ("kotlin/text/StringsKt" to "toBoolean") to Kind.BOOLEAN,
        ("kotlin/text/StringsKt" to "toBooleanStrictOrNull") to Kind.BOOLEAN,
        ("kotlin/text/StringsKt" to "toInt") to Kind.INTEGER_32,
        ("kotlin/text/StringsKt" to "toIntOrNull") to Kind.INTEGER_32,
        ("kotlin/text/StringsKt" to "toLong") to Kind.INTEGER_64,
        ("kotlin/text/StringsKt" to "toLongOrNull") to Kind.INTEGER_64,
        ("kotlin/text/StringsKt" to "toDouble") to Kind.NUMBER_64,
        ("kotlin/text/StringsKt" to "toDoubleOrNull") to Kind.NUMBER_64,
        ("kotlin/text/StringsKt" to "toFloat") to Kind.NUMBER_32,
        ("kotlin/text/StringsKt" to "toFloatOrNull") to Kind.NUMBER_32,
    )

    // --- client request builders -------------------------------------------
    const val CLIENT_REQUEST_KT = "io/ktor/client/request/HttpRequestKt"
    const val HTTP_REQUEST_BUILDER = "io/ktor/client/request/HttpRequestBuilder"
    const val HTTP_STATEMENT = "io/ktor/client/statement/HttpStatement"
    const val HTTP_CLIENT_CALL = "io/ktor/client/call/HttpClientCall"

    private const val GET_OR_CREATE_KOTLIN_CLASS = "getOrCreateKotlinClass"
    private const val TYPE_OF = "typeOf"
    private const val REFLECTION = "kotlin/jvm/internal/Reflection"

    /** Erased collection raw types that should become a Wirespec list at the use site. */
    private val COLLECTION_RAW = setOf(
        "java/util/List", "java/util/Collection", "java/util/Set", "java/lang/Iterable",
    )

    fun isCollectionRaw(internalName: String?): Boolean = internalName in COLLECTION_RAW

    /**
     * Map a Ktor `HttpStatusCode.Companion` getter name (e.g. `getCreated`) to its
     * numeric code. Unknown getters return null (caller falls back to a default).
     */
    fun statusFromGetter(getterName: String): Int? = STATUS_GETTERS[getterName]

    private val STATUS_GETTERS = mapOf(
        "getContinue" to 100, "getSwitchingProtocols" to 101, "getProcessing" to 102,
        "getOK" to 200, "getCreated" to 201, "getAccepted" to 202,
        "getNonAuthoritativeInformation" to 203, "getNoContent" to 204,
        "getResetContent" to 205, "getPartialContent" to 206, "getMultiStatus" to 207,
        "getMultipleChoices" to 300, "getMovedPermanently" to 301, "getFound" to 302,
        "getSeeOther" to 303, "getNotModified" to 304, "getTemporaryRedirect" to 307,
        "getPermanentRedirect" to 308,
        "getBadRequest" to 400, "getUnauthorized" to 401, "getPaymentRequired" to 402,
        "getForbidden" to 403, "getNotFound" to 404, "getMethodNotAllowed" to 405,
        "getNotAcceptable" to 406, "getProxyAuthenticationRequired" to 407,
        "getRequestTimeout" to 408, "getConflict" to 409, "getGone" to 410,
        "getLengthRequired" to 411, "getPreconditionFailed" to 412,
        "getPayloadTooLarge" to 413, "getRequestURITooLong" to 414,
        "getUnsupportedMediaType" to 415, "getUnprocessableEntity" to 422,
        "getLocked" to 423, "getFailedDependency" to 424, "getTooManyRequests" to 429,
        "getInternalServerError" to 500, "getNotImplemented" to 501,
        "getBadGateway" to 502, "getServiceUnavailable" to 503,
        "getGatewayTimeout" to 504, "getVersionNotSupported" to 505,
    )

    /** HTTP-verb getters on `HttpMethod.Companion` (client side). */
    val CLIENT_METHOD_GETTERS = mapOf(
        "getGet" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.GET,
        "getPost" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.POST,
        "getPut" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.PUT,
        "getPatch" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.PATCH,
        "getDelete" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.DELETE,
        "getHead" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.HEAD,
        "getOptions" to community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod.OPTIONS,
    )

    /** Reads a class (by internal name) into an ASM [ClassNode], or null when unavailable. */
    fun readClass(loader: ClassLoader, internalName: String): ClassNode? {
        val stream = loader.getResourceAsStream("$internalName.class") ?: return null
        return stream.use {
            val node = ClassNode()
            ClassReader(it).accept(node, ClassReader.SKIP_FRAMES)
            node
        }
    }

    /** True if any method in [cn] invokes a member of one of [owners]. */
    fun referencesOwner(cn: ClassNode, owners: Set<String>): Boolean =
        cn.methods.any { m ->
            var i: AbstractInsnNode? = m.instructions?.first
            while (i != null) {
                if (i is MethodInsnNode && i.owner in owners) return@any true
                i = i.next
            }
            false
        }

    /**
     * Tracks the `TypeInfo(KClass, KType)` construction the Kotlin compiler inlines
     * for every reified `receive`/`respond`/`setBody`/`body` call. Feed it each
     * instruction in order; after a `TypeInfo.<init>` it exposes the most recently
     * committed (rawClass, elementClass) pair.
     */
    class TypeInfoTracker {
        private var lastClassLdc: String? = null
        private var pendingRaw: String? = null
        private var pendingElement: String? = null

        /** Internal name of the raw `KClass` of the last committed TypeInfo. */
        var committedRaw: String? = null
            private set

        /** Internal name of the (single) type argument of the last committed TypeInfo, if any. */
        var committedElement: String? = null
            private set

        fun onInsn(insn: AbstractInsnNode) {
            when (insn) {
                is LdcInsnNode -> (insn.cst as? Type)
                    ?.takeIf { it.sort == Type.OBJECT }
                    ?.let { lastClassLdc = it.internalName }

                is TypeInsnNode -> Unit

                is MethodInsnNode -> when {
                    insn.owner == REFLECTION && insn.name == GET_OR_CREATE_KOTLIN_CLASS ->
                        pendingRaw = lastClassLdc
                    insn.owner == REFLECTION && insn.name == TYPE_OF &&
                        insn.desc == "(Ljava/lang/Class;)Lkotlin/reflect/KType;" ->
                        pendingElement = lastClassLdc
                    insn.owner == TYPE_INFO && insn.name == "<init>" -> {
                        committedRaw = pendingRaw
                        committedElement = pendingElement
                        pendingRaw = null
                        pendingElement = null
                    }
                }
            }
        }
    }
}
