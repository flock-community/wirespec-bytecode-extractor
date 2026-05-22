package community.flock.wirespec.spring.extractor.extract.dsl

import community.flock.wirespec.spring.extractor.extract.ApiResponseExtractor
import community.flock.wirespec.spring.extractor.extract.ReturnTypeUnwrapper
import community.flock.wirespec.spring.extractor.extract.TypeExtractor
import community.flock.wirespec.spring.extractor.model.Endpoint
import community.flock.wirespec.spring.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.spring.extractor.model.WireType
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type as JType

/**
 * Builds [Endpoint] values from raw [DslRoute]s. Handler methods are looked up
 * by reflective signature; their request body is inferred by scanning their
 * bytecode for `ServerRequest.bodyToMono/bodyToFlux/body(Class)` calls.
 *
 * Responses fall back to a single 200 entry with no body unless the handler
 * carries `@ApiResponses` / `@ApiResponse` annotations, which are honored by
 * the existing [ApiResponseExtractor].
 */
internal class DslEndpointExtractor(
    private val types: TypeExtractor,
    private val classLoader: ClassLoader,
    private val onWarn: (String) -> Unit = {},
) {

    private val apiResponses = ApiResponseExtractor(types, onWarn)

    fun extract(configClass: Class<*>, routes: List<DslRoute>): List<Endpoint> {
        if (routes.isEmpty()) return emptyList()
        val configSimple = configClass.simpleName
        val nameCounts = mutableMapOf<String, Int>()
        return routes.map { route -> buildEndpoint(configSimple, route, nameCounts) }
    }

    private fun buildEndpoint(
        configSimple: String,
        route: DslRoute,
        nameCounts: MutableMap<String, Int>,
    ): Endpoint {
        val handler = resolveHandler(route)
        val pathSegments = parsePath(route.path)
        val requestBody = handler?.let { inferRequestBody(it) }
        val responses = buildResponses(handler)
        val baseName = endpointName(route, handler)
        val occurrence = nameCounts.merge(baseName, 1, Int::plus) ?: 1
        val finalName = if (occurrence == 1) baseName else "$baseName$occurrence"
        return Endpoint(
            controllerSimpleName = configSimple,
            name = finalName,
            method = route.method,
            pathSegments = pathSegments,
            queryParams = emptyList(),
            headerParams = emptyList(),
            cookieParams = emptyList(),
            requestBody = requestBody,
            responses = responses,
        )
    }

    private fun endpointName(route: DslRoute, handler: Method?): String {
        val basis = handler?.name ?: route.handlerName ?: route.method.name.lowercase()
        return basis.replaceFirstChar { it.uppercase() }
    }

    private fun parsePath(path: String): List<PathSegment> =
        path.split('/').filter { it.isNotBlank() }.map { seg ->
            val match = Regex("""^\{([^:}]+)(?::[^}]+)?}$""").matchEntire(seg)
            if (match != null) PathSegment.Variable(match.groupValues[1], WireType.Primitive(WireType.Primitive.Kind.STRING))
            else PathSegment.Literal(seg)
        }

    private fun buildResponses(handler: Method?): List<Endpoint.Response> {
        if (handler == null) return listOf(Endpoint.Response(200, null))
        // For DSL handlers we can't infer the natural status/body from the
        // signature (it's always `ServerResponse`/`Mono<ServerResponse>`), so the
        // default is `200` with no body. If the handler explicitly declares
        // `@ApiResponses`/`@ApiResponse`, defer to ApiResponseExtractor with a
        // sentinel that makes 200 the natural status and absent the natural body.
        val sentinel = ReturnTypeUnwrapper.Unwrapped(
            type = Any::class.java,
            isList = false,
            isVoid = false,
        )
        val extracted = apiResponses.extract(handler, sentinel)
        // ApiResponseExtractor's "no annotations" path would otherwise hit
        // `types.extract(Any::class.java)` and produce a STRING body, which is
        // wrong for a routing-DSL handler whose response shape is unknowable here.
        return if (handlerHasApiResponseAnnotations(handler)) extracted
        else listOf(Endpoint.Response(200, null))
    }

    private fun handlerHasApiResponseAnnotations(handler: Method): Boolean {
        val all = handler.annotations + handler.declaringClass.annotations
        return all.any {
            val n = it.annotationClass.qualifiedName
            n == "io.swagger.v3.oas.annotations.responses.ApiResponses"
                || n == "io.swagger.v3.oas.annotations.responses.ApiResponse"
        }
    }

    private fun resolveHandler(route: DslRoute): Method? {
        val owner = route.handlerOwnerInternal ?: return null
        val name = route.handlerName ?: return null
        val desc = route.handlerDescriptor ?: return null
        val cls = loadClass(owner) ?: return null
        return findMethod(cls, name, desc)
    }

    private fun loadClass(internalName: String): Class<*>? {
        val fqn = internalName.replace('/', '.')
        return try { Class.forName(fqn, false, classLoader) }
        catch (t: Throwable) {
            onWarn("Could not load DSL handler class $fqn: ${t.message}")
            null
        }
    }

    private fun findMethod(cls: Class<*>, name: String, desc: String): Method? {
        val argTypes = Type.getArgumentTypes(desc)
        return cls.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterCount == argTypes.size && matchesDescriptor(m, argTypes)
        } ?: cls.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == argTypes.size && matchesDescriptor(m, argTypes)
        }
    }

    private fun matchesDescriptor(method: Method, argTypes: Array<Type>): Boolean {
        val params = method.parameterTypes
        for (i in argTypes.indices) {
            val expected = argTypes[i].className
            if (params[i].name != expected) return false
        }
        return true
    }

    /**
     * Scan [handler]'s bytecode for the conventional request-body extractor
     * calls (`ServerRequest.bodyToMono`/`bodyToFlux`/`body(Class)`/etc.) and
     * return the inferred Wirespec body type, or `null` when no extractor is
     * found or the argument is not a class literal.
     */
    private fun inferRequestBody(handler: Method): WireType? {
        // Skip handlers without a body or compiled in a way ASM can't see (rare).
        val owner = handler.declaringClass
        val cn = readClass(owner) ?: return null
        // Match against the bytecode descriptor (which preserves overloads).
        val mn = cn.methods.firstOrNull { it.name == handler.name && it.desc == descriptorOf(handler) }
            ?: return null
        return findRequestBodyType(mn)
    }

    private fun findRequestBodyType(method: MethodNode): WireType? {
        val insns = method.instructions ?: return null
        var lastClassLiteral: Class<*>? = null
        var i: AbstractInsnNode? = insns.first
        while (i != null) {
            when (i) {
                is LdcInsnNode -> {
                    val cst = i.cst
                    if (cst is Type && cst.sort == Type.OBJECT) {
                        lastClassLiteral = loadClass(cst.internalName)
                    }
                }
                is MethodInsnNode -> if (isBodyExtractorCall(i)) {
                    val target = lastClassLiteral ?: return null
                    val raw = types.extract(target)
                    return if (i.name == "bodyToFlux") WireType.ListOf(raw) else raw
                }
            }
            i = i.next
        }
        return null
    }

    private fun isBodyExtractorCall(insn: MethodInsnNode): Boolean {
        val isServerRequest =
            insn.owner == "org/springframework/web/reactive/function/server/ServerRequest"
                || insn.owner == "org/springframework/web/servlet/function/ServerRequest"
        if (!isServerRequest) return false
        // We only recognise the `Class<T>` overloads here — ParameterizedTypeReference
        // pre-allocates a synthetic subclass and is harder to read statically.
        return insn.name == "bodyToMono"
            || insn.name == "bodyToFlux"
            || insn.name == "body"
    }

    private fun descriptorOf(method: Method): String {
        val sb = StringBuilder("(")
        for (p in method.parameterTypes) sb.append(Type.getDescriptor(p))
        sb.append(')').append(Type.getDescriptor(method.returnType))
        return sb.toString()
    }

    private fun readClass(cls: Class<*>): ClassNode? {
        val resource = cls.name.replace('.', '/') + ".class"
        val loader = cls.classLoader ?: classLoader
        val stream = loader.getResourceAsStream(resource) ?: return null
        return stream.use {
            val reader = ClassReader(it)
            val node = ClassNode()
            reader.accept(node, ClassReader.SKIP_FRAMES)
            node
        }
    }

    @Suppress("unused") // kept for future generic-aware handler signatures
    private fun typeArgOrSelf(t: JType): JType =
        if (t is ParameterizedType) t.actualTypeArguments.firstOrNull() ?: t else t

    @Suppress("unused")
    private fun opcodes() = Opcodes.ASM9
}
