package community.flock.wirespec.extractor.extract.ktor

import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Static bytecode walker that discovers Ktor **client** HTTP calls inside a
 * class's methods.
 *
 * Ktor's `client.get(url) { … }` / `post` / … request builders are `inline`, so
 * by the time we see the bytecode they have been expanded at the call site into
 * a `HttpRequestBuilder` lifecycle:
 *
 *  - `HttpRequestKt.url(builder, "/path")` — the request URL.
 *  - `HttpMethod.Companion.getXxx()` (followed by `builder.setMethod`) — the verb.
 *  - `HttpRequestBuilder.setBodyType(TypeInfo)` — the `setBody<T>(…)` payload.
 *  - `HttpClientCall.bodyNullable(TypeInfo)` — the `.body<T>()` response type.
 *
 * Each `new HttpRequestBuilder()` opens a fresh call; the surrounding method may
 * issue several. Returns one [KtorClientCall] per detected request that has a
 * resolvable HTTP method.
 */
internal object KtorClientWalker {

    fun walk(clazz: Class<*>): Map<String, List<KtorClientCall>> {
        val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
        val cn = KtorBytecode.readClass(loader, clazz.name.replace('.', '/')) ?: return emptyMap()
        val result = linkedMapOf<String, List<KtorClientCall>>()
        for (m in cn.methods) {
            if (m.name == "<init>" || m.name == "<clinit>") continue
            val calls = walkMethod(m)
            if (calls.isNotEmpty()) result[methodDisplayName(m)] = calls
        }
        return result
    }

    /** Drops the trailing `$suspendImpl` / coroutine continuation noise from a method name. */
    private fun methodDisplayName(m: MethodNode): String = m.name.substringBefore('$')

    private class Builder {
        var method: HttpMethod? = null
        var path: String? = null
        var reqRaw: String? = null
        var reqElem: String? = null
        var respRaw: String? = null
        var respElem: String? = null

        fun toCall(): KtorClientCall? {
            val verb = method ?: return null
            return KtorClientCall(verb, path, reqRaw, reqElem, respRaw, respElem)
        }
    }

    private fun walkMethod(method: MethodNode): List<KtorClientCall> {
        val tracker = KtorBytecode.TypeInfoTracker()
        val calls = mutableListOf<KtorClientCall>()
        var current: Builder? = null
        var lastString: String? = null

        var i: AbstractInsnNode? = method.instructions?.first
        while (i != null) {
            tracker.onInsn(i)
            when (i) {
                is LdcInsnNode -> if (i.cst is String) lastString = i.cst as String

                is MethodInsnNode -> when {
                    i.owner == KtorBytecode.HTTP_REQUEST_BUILDER && i.name == "<init>" -> {
                        current?.toCall()?.let { calls += it }
                        current = Builder()
                    }
                    current != null && i.owner == KtorBytecode.HTTP_METHOD_COMPANION ->
                        KtorBytecode.CLIENT_METHOD_GETTERS[i.name]?.let { current!!.method = it }
                    current != null && i.owner == KtorBytecode.CLIENT_REQUEST_KT && i.name == "url" ->
                        current!!.path = lastString
                    current != null && i.owner == KtorBytecode.HTTP_REQUEST_BUILDER && i.name == "setBodyType" -> {
                        current!!.reqRaw = tracker.committedRaw
                        current!!.reqElem = tracker.committedElement
                    }
                    current != null && isResponseBodyCall(i) -> {
                        current!!.respRaw = tracker.committedRaw
                        current!!.respElem = tracker.committedElement
                    }
                }
            }
            i = i.next
        }
        current?.toCall()?.let { calls += it }
        return calls
    }

    private fun isResponseBodyCall(insn: MethodInsnNode): Boolean =
        (insn.owner == KtorBytecode.HTTP_CLIENT_CALL || insn.owner == KtorBytecode.HTTP_STATEMENT) &&
            (insn.name == "body" || insn.name == "bodyNullable") &&
            insn.desc.contains("Lio/ktor/util/reflect/TypeInfo;")
}
