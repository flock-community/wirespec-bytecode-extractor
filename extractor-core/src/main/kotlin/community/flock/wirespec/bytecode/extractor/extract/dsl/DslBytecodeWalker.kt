package community.flock.wirespec.bytecode.extractor.extract.dsl

import community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.InputStream

/**
 * Static bytecode walker that discovers Spring functional-DSL routes inside a
 * configuration class.
 *
 * Recognises three flavors:
 *   - WebFlux Kotlin DSL (`org.springframework.web.reactive.function.server.RouterFunctionDsl`,
 *     plus the suspend-capable `CoRouterFunctionDsl`).
 *   - Spring MVC Kotlin DSL (`org.springframework.web.servlet.function.RouterFunctionDsl`).
 *   - Java fluent builder (`org.springframework.web.reactive.function.server.RouterFunctions$Builder`
 *     and `org.springframework.web.servlet.function.RouterFunctions$Builder`).
 *
 * Strategy: scan each method linearly while tracking the most recent path
 * string and the most recent "handler reference" pushed onto the stack. When a
 * DSL invocation (`GET`/`POST`/...) is encountered, those values are consumed.
 * `nest`/`path` invocations follow the inner lambda (either an INVOKEDYNAMIC
 * `LambdaMetafactory` target or a generated inner class) with the active
 * path prefix extended.
 *
 * Handler references come in two compiled shapes:
 *   - INVOKEDYNAMIC against `java/lang/invoke/LambdaMetafactory` — used for
 *     Java method references and Kotlin lambdas under indy-lambda compilation.
 *   - `NEW Outer$lambda$N; INVOKESPECIAL <init>` — used by Kotlin for
 *     `h::method`, generating a `FunctionReferenceImpl` subclass whose
 *     constructor embeds the target class, method name, and signature as LDC
 *     constants. We resolve those by re-reading the generated class.
 */
internal object DslBytecodeWalker {

    private const val WEBFLUX_DSL = "org/springframework/web/reactive/function/server/RouterFunctionDsl"
    private const val WEBFLUX_CO_DSL = "org/springframework/web/reactive/function/server/CoRouterFunctionDsl"
    private const val WEBFLUX_DSL_KT = "org/springframework/web/reactive/function/server/RouterFunctionDslKt"
    private const val WEBFLUX_CO_DSL_KT = "org/springframework/web/reactive/function/server/CoRouterFunctionDslKt"
    private const val WEBFLUX_BUILDER = "org/springframework/web/reactive/function/server/RouterFunctions\$Builder"

    private const val MVC_DSL = "org/springframework/web/servlet/function/RouterFunctionDsl"
    private const val MVC_DSL_KT = "org/springframework/web/servlet/function/RouterFunctionDslKt"
    private const val MVC_BUILDER = "org/springframework/web/servlet/function/RouterFunctions\$Builder"

    private val DSL_OWNERS = setOf(
        WEBFLUX_DSL, WEBFLUX_CO_DSL, WEBFLUX_DSL_KT, WEBFLUX_CO_DSL_KT, WEBFLUX_BUILDER,
        MVC_DSL, MVC_DSL_KT, MVC_BUILDER,
    )

    private val HTTP_METHODS = mapOf(
        "GET" to HttpMethod.GET,
        "POST" to HttpMethod.POST,
        "PUT" to HttpMethod.PUT,
        "PATCH" to HttpMethod.PATCH,
        "DELETE" to HttpMethod.DELETE,
        "HEAD" to HttpMethod.HEAD,
        "OPTIONS" to HttpMethod.OPTIONS,
    )

    /** Captures whatever pointer we currently know about an upcoming handler argument. */
    private sealed interface HandlerRef {
        /** INVOKEDYNAMIC `LambdaMetafactory` — points at the lambda's implementation method. */
        data class IndyHandle(val handle: Handle) : HandlerRef

        /** Kotlin `h::method` style: a generated inner class whose constructor encodes target info. */
        data class FunctionRefClass(val internalName: String) : HandlerRef
    }

    fun walk(clazz: Class<*>): List<DslRoute> {
        val cn = readClass(clazz.classLoader ?: ClassLoader.getSystemClassLoader(), classResource(clazz.name)) ?: return emptyList()
        val out = mutableListOf<DslRoute>()
        // Walk only methods whose declared return type is `RouterFunction` — Kotlin
        // generates private static helpers for lambda bodies in the same class
        // (`routes$lambda$N`, returning `kotlin/Unit`), and those are reached via
        // recursive descent. Walking them at the top level would duplicate routes
        // and lose their path prefix.
        val byName: Map<Pair<String, String>, MethodNode> = cn.methods.associateBy { it.name to it.desc }
        for (m in cn.methods) {
            if ((m.access and Opcodes.ACC_BRIDGE) != 0) continue
            if (!returnsRouterFunction(m)) continue
            walkMethod(cn.name, m, byName, prefix = "", out, clazz.classLoader)
        }
        return out
    }

    private fun returnsRouterFunction(method: MethodNode): Boolean {
        val ret = Type.getReturnType(method.desc)
        if (ret.sort != Type.OBJECT) return false
        return ret.internalName == "org/springframework/web/reactive/function/server/RouterFunction"
            || ret.internalName == "org/springframework/web/servlet/function/RouterFunction"
    }

    private fun walkMethod(
        owner: String,
        method: MethodNode,
        byName: Map<Pair<String, String>, MethodNode>,
        prefix: String,
        out: MutableList<DslRoute>,
        loader: ClassLoader?,
    ) {
        val insns = method.instructions ?: return
        var lastString: String? = null
        var lastHandler: HandlerRef? = null
        var pendingNew: String? = null  // NEW typeInternalName not yet committed by INVOKESPECIAL <init>

        var i: AbstractInsnNode? = insns.first
        while (i != null) {
            when (i) {
                is LdcInsnNode -> if (i.cst is String) lastString = i.cst as String

                is TypeInsnNode -> if (i.opcode == Opcodes.NEW) {
                    pendingNew = i.desc
                }

                is InvokeDynamicInsnNode -> {
                    val handle = lambdaImplementationHandle(i)
                    if (handle != null) lastHandler = HandlerRef.IndyHandle(handle)
                }

                is MethodInsnNode -> {
                    if (i.opcode == Opcodes.INVOKESPECIAL && i.name == "<init>" && pendingNew != null) {
                        lastHandler = HandlerRef.FunctionRefClass(pendingNew!!)
                        pendingNew = null
                    }
                    if (i.owner in DSL_OWNERS) {
                        val httpMethod = HTTP_METHODS[i.name]
                        when {
                            httpMethod != null && lastString != null -> {
                                val path = joinPath(prefix, lastString!!)
                                val resolved = resolveHandler(lastHandler, loader)
                                out += DslRoute(
                                    method = httpMethod,
                                    path = path,
                                    handlerOwnerInternal = resolved?.owner,
                                    handlerName = resolved?.name,
                                    handlerDescriptor = resolved?.desc,
                                )
                            }
                            i.name == "nest" || i.name == "path" -> {
                                val nestPrefix = lastString?.let { joinPath(prefix, it) } ?: prefix
                                followNestedLambda(owner, lastHandler, byName, nestPrefix, out, loader)
                            }
                            (i.name == "router" || i.name == "coRouter") && i.owner.endsWith("Kt") -> {
                                followNestedLambda(owner, lastHandler, byName, prefix, out, loader)
                            }
                        }
                    } else if (isKotlinDslEntryFunction(i)) {
                        followNestedLambda(owner, lastHandler, byName, prefix, out, loader)
                    }
                }
            }
            i = i.next
        }
    }

    private fun followNestedLambda(
        owner: String,
        handler: HandlerRef?,
        byName: Map<Pair<String, String>, MethodNode>,
        prefix: String,
        out: MutableList<DslRoute>,
        loader: ClassLoader?,
    ) {
        when (handler) {
            is HandlerRef.IndyHandle -> {
                val h = handler.handle
                if (h.owner == owner) {
                    byName[h.name to h.desc]?.let { target ->
                        walkMethod(owner, target, byName, prefix, out, loader)
                    }
                }
            }
            is HandlerRef.FunctionRefClass -> {
                // A nest body materialised as an inner class — uncommon in practice (Kotlin
                // typically uses indy for nest's lambda arg), but handle it gracefully by
                // following the inner class's `invoke` method.
                val ldr = loader ?: return
                val cn = readClass(ldr, "${handler.internalName}.class") ?: return
                cn.methods
                    .firstOrNull { it.name == "invoke" && it.desc.endsWith("V") }
                    ?.let { walkMethod(cn.name, it, cn.methods.associateBy { m -> m.name to m.desc }, prefix, out, ldr) }
            }
            null -> Unit
        }
    }

    /**
     * Resolve a handler reference to the actual target method's (owner, name, desc).
     *
     * For [HandlerRef.IndyHandle] this is just the embedded `Handle`. For
     * [HandlerRef.FunctionRefClass] (Kotlin `h::method`), we read the generated
     * class's `<init>` method, which contains the target as three LDC constants
     * (class literal, method name, signature) on the way to its
     * `FunctionReferenceImpl` super-constructor — see
     * `FunctionReferenceImpl(int arity, Object receiver, Class owner, String name, String signature, int flags)`.
     */
    private fun resolveHandler(ref: HandlerRef?, loader: ClassLoader?): Handle? {
        return when (ref) {
            is HandlerRef.IndyHandle -> ref.handle
            is HandlerRef.FunctionRefClass -> {
                val ldr = loader ?: return null
                val cn = readClass(ldr, "${ref.internalName}.class") ?: return null
                resolveFromFunctionReferenceImpl(cn) ?: resolveFromInvokeMethod(cn)
            }
            null -> null
        }
    }

    /**
     * Pick the call out of `<init>`'s LDC constants: the three string-or-class
     * LDCs immediately before the `invokespecial FunctionReferenceImpl.<init>`
     * are (target class, method name, signature). Signature is `name(desc)retDesc`,
     * so we split off the parens to get the JVM descriptor.
     */
    private fun resolveFromFunctionReferenceImpl(cn: ClassNode): Handle? {
        val ctor = cn.methods.firstOrNull { it.name == "<init>" } ?: return null
        val ldcs = mutableListOf<Any>()
        var i: AbstractInsnNode? = ctor.instructions.first
        var initCall: MethodInsnNode? = null
        while (i != null) {
            when (i) {
                is LdcInsnNode -> ldcs += i.cst
                is MethodInsnNode -> if (i.name == "<init>" && (
                        i.owner == "kotlin/jvm/internal/FunctionReferenceImpl" ||
                        i.owner == "kotlin/jvm/internal/PropertyReference" ||
                        i.owner.startsWith("kotlin/jvm/internal/")
                    )) initCall = i
            }
            i = i.next
        }
        if (initCall == null) return null
        // Walk LDC constants from the end backwards: signature, name, owner class.
        val classLdc = ldcs.firstOrNull { it is Type && it.sort == Type.OBJECT } as? Type ?: return null
        val stringLdcs = ldcs.filterIsInstance<String>()
        // Heuristic: name is the short string ("list"), signature contains '(' ')'.
        val signature = stringLdcs.firstOrNull { it.contains('(') } ?: return null
        val name = stringLdcs.firstOrNull { !it.contains('(') } ?: return null
        val paren = signature.indexOf('(')
        if (paren < 0) return null
        val desc = signature.substring(paren)
        return Handle(Opcodes.H_INVOKEVIRTUAL, classLdc.internalName, name, desc, false)
    }

    /**
     * Fallback when the FunctionReferenceImpl constants aren't recognisable:
     * scan the inner class's `invoke(...)` method for its single delegated
     * `INVOKE*` and use that.
     */
    private fun resolveFromInvokeMethod(cn: ClassNode): Handle? {
        val invokes = cn.methods.filter { it.name == "invoke" }
        for (m in invokes) {
            var i: AbstractInsnNode? = m.instructions.first
            while (i != null) {
                if (i is MethodInsnNode && i.opcode != Opcodes.INVOKESPECIAL) {
                    val tag = when (i.opcode) {
                        Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC
                        Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE
                        else -> Opcodes.H_INVOKEVIRTUAL
                    }
                    // Skip Intrinsics.checkNotNullParameter etc.
                    if (!i.owner.startsWith("kotlin/jvm/internal/")
                        && !i.owner.startsWith("java/")
                        && i.name != "invoke"
                    ) return Handle(tag, i.owner, i.name, i.desc, i.opcode == Opcodes.INVOKEINTERFACE)
                }
                i = i.next
            }
        }
        return null
    }

    private fun isKotlinDslEntryFunction(insn: MethodInsnNode): Boolean {
        if (insn.owner !in setOf(WEBFLUX_DSL_KT, WEBFLUX_CO_DSL_KT, MVC_DSL_KT)) return false
        return insn.name == "router" || insn.name == "coRouter"
    }

    private fun lambdaImplementationHandle(insn: InvokeDynamicInsnNode): Handle? {
        val bsm = insn.bsm ?: return null
        if (bsm.owner != "java/lang/invoke/LambdaMetafactory") return null
        val args = insn.bsmArgs ?: return null
        return args.firstNotNullOfOrNull { it as? Handle }
    }

    private fun joinPath(prefix: String, segment: String): String {
        val left = prefix.trim('/').takeIf { it.isNotBlank() }
        val right = segment.trim('/').takeIf { it.isNotBlank() }
        return when {
            left == null && right == null -> ""
            left == null -> "/$right"
            right == null -> "/$left"
            else -> "/$left/$right"
        }
    }

    private fun classResource(fqn: String): String = fqn.replace('.', '/') + ".class"

    private fun readClass(loader: ClassLoader, resource: String): ClassNode? {
        val stream: InputStream = loader.getResourceAsStream(resource) ?: return null
        return stream.use {
            val reader = ClassReader(it)
            val node = ClassNode()
            reader.accept(node, ClassReader.SKIP_FRAMES)
            node
        }
    }
}
