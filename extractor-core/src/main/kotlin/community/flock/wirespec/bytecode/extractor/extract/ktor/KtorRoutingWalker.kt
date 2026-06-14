package community.flock.wirespec.bytecode.extractor.extract.ktor

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

/**
 * Static bytecode walker that discovers Ktor **server** routes inside a class
 * holding a `routing { … }` block (typically a top-level `Application.module()`
 * function, compiled into a `…Kt` file facade).
 *
 * Strategy mirrors the Spring functional-DSL walker: scan each method linearly,
 * tracking the most recent path string and the most recent handler lambda. Ktor
 * compiles the configuration lambdas of `routing { }` / `route(path) { }` as
 * `invokedynamic` targets (private synthetic methods in the same class), which
 * are followed recursively with the path prefix extended. The `get { }` /
 * `post { }` / … HTTP handlers are suspend lambdas compiled to generated inner
 * classes; their internal name is captured so [KtorEndpointExtractor] can read
 * the body later.
 *
 * No Ktor code is executed and no application is booted — this is pure static
 * inspection, like the Spring DSL path.
 */
internal object KtorRoutingWalker {

    private val NEST_FUNCTIONS = setOf("route")

    fun walk(clazz: Class<*>): List<KtorRoute> {
        val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
        val cn = KtorBytecode.readClass(loader, clazz.name.replace('.', '/')) ?: return emptyList()
        val byName = cn.methods.associateBy { it.name to it.desc }
        val out = mutableListOf<KtorRoute>()
        val visited = mutableSetOf<Pair<String, String>>()
        for (m in cn.methods) {
            // Skip the synthetic indy-lambda helpers (`userRoutes$lambda$0`, …): they are the
            // config bodies of `routing { }` / `route(…) { }` and are reached via recursive
            // descent with the correct path prefix. Walking them at the top level would emit
            // duplicate, prefix-less routes.
            if (m.name.contains('$') || (m.access and Opcodes.ACC_SYNTHETIC) != 0) continue
            if (m.name == "<init>" || m.name == "<clinit>") continue
            if (!declaresRouting(m)) continue
            walkMethod(cn, m, byName, prefix = "", out, visited)
        }
        return out
    }

    /** Only enter methods that themselves call `routing { }` / a routing builder — the entry points. */
    private fun declaresRouting(method: MethodNode): Boolean {
        var i: AbstractInsnNode? = method.instructions?.first
        while (i != null) {
            if (i is MethodInsnNode && (i.owner == KtorBytecode.ROUTING_ROOT || i.owner == KtorBytecode.ROUTING_BUILDER)) return true
            i = i.next
        }
        return false
    }

    private fun walkMethod(
        cn: ClassNode,
        method: MethodNode,
        byName: Map<Pair<String, String>, MethodNode>,
        prefix: String,
        out: MutableList<KtorRoute>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        // Guard against cycles (a method reached via two indy paths) while still
        // allowing the same helper to be walked under different prefixes.
        if (!visited.add(method.name to (method.desc + "@" + prefix))) return

        var lastString: String? = null
        var lastHandlerClass: String? = null
        var lastIndy: Handle? = null
        var pendingNew: String? = null

        var i: AbstractInsnNode? = method.instructions?.first
        while (i != null) {
            when (i) {
                is LdcInsnNode -> if (i.cst is String) lastString = i.cst as String

                is TypeInsnNode -> if (i.opcode == Opcodes.NEW) pendingNew = i.desc

                is InvokeDynamicInsnNode -> lambdaHandle(i)?.let { lastIndy = it }

                is MethodInsnNode -> {
                    if (i.opcode == Opcodes.INVOKESPECIAL && i.name == "<init>" && pendingNew != null) {
                        lastHandlerClass = pendingNew
                        pendingNew = null
                    }
                    when {
                        // routing { } — enter the config lambda with the current prefix.
                        i.owner == KtorBytecode.ROUTING_ROOT && i.name == "routing" -> {
                            followIndy(cn, lastIndy, byName, prefix, out, visited)
                            lastString = null; lastIndy = null; lastHandlerClass = null
                        }
                        i.owner == KtorBytecode.ROUTING_BUILDER -> {
                            val httpMethod = KtorBytecode.SERVER_HTTP_METHODS[i.name]
                            when {
                                httpMethod != null -> {
                                    val inlinePath = if (hasStringArg(i.desc)) lastString else null
                                    out += KtorRoute(
                                        method = httpMethod,
                                        path = joinPath(prefix, inlinePath),
                                        handlerClassInternal = lastHandlerClass,
                                    )
                                }
                                i.name in NEST_FUNCTIONS -> {
                                    val nestPrefix = if (hasStringArg(i.desc)) joinPath(prefix, lastString) else prefix
                                    followIndy(cn, lastIndy, byName, nestPrefix, out, visited)
                                }
                            }
                            lastString = null; lastIndy = null; lastHandlerClass = null
                        }
                    }
                }
            }
            i = i.next
        }
    }

    private fun followIndy(
        cn: ClassNode,
        handle: Handle?,
        byName: Map<Pair<String, String>, MethodNode>,
        prefix: String,
        out: MutableList<KtorRoute>,
        visited: MutableSet<Pair<String, String>>,
    ) {
        val h = handle ?: return
        if (h.owner != cn.name) return
        val target = byName[h.name to h.desc] ?: cn.methods.firstOrNull { it.name == h.name } ?: return
        walkMethod(cn, target, byName, prefix, out, visited)
    }

    private fun lambdaHandle(insn: InvokeDynamicInsnNode): Handle? {
        if (insn.bsm?.owner != "java/lang/invoke/LambdaMetafactory") return null
        return insn.bsmArgs?.firstNotNullOfOrNull { it as? Handle }
    }

    private fun hasStringArg(desc: String): Boolean =
        Type.getArgumentTypes(desc).any { it.className == "java.lang.String" }

    private fun joinPath(prefix: String, segment: String?): String {
        val left = prefix.trim('/').takeIf { it.isNotBlank() }
        val right = segment?.trim('/')?.takeIf { it.isNotBlank() }
        return when {
            left == null && right == null -> ""
            left == null -> "/$right"
            right == null -> "/$left"
            else -> "/$left/$right"
        }
    }
}
