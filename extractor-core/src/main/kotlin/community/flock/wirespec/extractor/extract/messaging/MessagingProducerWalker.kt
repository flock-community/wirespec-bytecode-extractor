package community.flock.wirespec.extractor.extract.messaging

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type as AsmType
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Discovers producer send-call sites in a class by linear bytecode scan.
 *
 * For each `INVOKE <sendMethod>` on the broker's template, the most recent
 * `GETFIELD <template>` identifies the field it was invoked on. The payload type
 * is then recovered:
 *  - Generic templates (Kafka, Pulsar): from the field's recovered value class.
 *  - Non-generic templates (JMS, Rabbit): best-effort from the static type of the
 *    last value-producing instruction before the INVOKE (local/param, field,
 *    method return, or `new X(...)`). Unresolvable / JDK-noise / MessagePostProcessor
 *    overloads are warn-skipped.
 *
 * Returns one [ProducerSite] per (enclosingMethod, valueClass) tuple.
 */
internal object MessagingProducerWalker {

    private const val MESSAGE_POST_PROCESSOR_SIMPLE = "MessagePostProcessor"

    data class ProducerSite(
        val ownerClass: Class<*>,
        val enclosingMethod: String,
        val valueClass: Class<*>,
    )

    fun walk(
        clazz: Class<*>,
        templateFields: List<MessagingProducerScanner.TemplateField>,
        broker: MessagingBroker,
        onWarn: (String) -> Unit = {},
    ): List<ProducerSite> {
        val producer = broker.producer ?: return emptyList()
        val fieldsForClass = templateFields.filter { it.ownerClass == clazz }
        if (fieldsForClass.isEmpty()) return emptyList()
        val fieldByName = fieldsForClass.associateBy { it.fieldName }

        val templateInternal = when (producer) {
            is ProducerSpec.GenericTemplate    -> producer.fqn.replace('.', '/')
            is ProducerSpec.NonGenericTemplate -> producer.fqn.replace('.', '/')
        }
        val templateDesc = "L$templateInternal;"
        val sendMethods = when (producer) {
            is ProducerSpec.GenericTemplate    -> producer.sendMethods
            is ProducerSpec.NonGenericTemplate -> producer.sendMethods
        }

        val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
        val cn = readClass(loader, classResource(clazz.name)) ?: return emptyList()

        val seen = linkedSetOf<ProducerSite>()
        for (m in cn.methods) {
            if ((m.access and Opcodes.ACC_BRIDGE) != 0) continue
            if ((m.access and Opcodes.ACC_SYNTHETIC) != 0) continue
            var lastTemplateField: String? = null
            var i: AbstractInsnNode? = m.instructions?.first
            while (i != null) {
                when (i) {
                    is FieldInsnNode -> if (i.opcode == Opcodes.GETFIELD && i.desc == templateDesc) {
                        lastTemplateField = i.name
                    }
                    is MethodInsnNode -> if (i.owner == templateInternal && i.name in sendMethods) {
                        val tf = lastTemplateField?.let(fieldByName::get)
                        if (tf != null) {
                            val value: Class<*>? = when (producer) {
                                is ProducerSpec.GenericTemplate -> tf.valueClass
                                is ProducerSpec.NonGenericTemplate ->
                                    resolvePayloadType(i, m, clazz, loader, onWarn)
                            }
                            if (value != null) seen += ProducerSite(clazz, m.name, value)
                        }
                    }
                }
                i = i.next
            }
        }
        return seen.toList()
    }

    /** Best-effort static-type recovery of the payload arg for a non-generic send. */
    private fun resolvePayloadType(
        invoke: MethodInsnNode,
        method: MethodNode,
        clazz: Class<*>,
        loader: ClassLoader,
        onWarn: (String) -> Unit,
    ): Class<*>? {
        // MessagePostProcessor overloads: payload is not the last arg → skip.
        if (AsmType.getArgumentTypes(invoke.desc).any { it.className.endsWith(".$MESSAGE_POST_PROCESSOR_SIMPLE") }) {
            onWarn("messaging.producer: skipping ${clazz.name}.${method.name}: MessagePostProcessor send overload not supported")
            return null
        }
        val prev = prevMeaningful(invoke.previous)
        val fqn: String? = when (prev) {
            is VarInsnNode ->
                if (prev.opcode == Opcodes.ALOAD) localType(method, prev.`var`) else null
            is FieldInsnNode ->
                if (prev.opcode == Opcodes.GETFIELD || prev.opcode == Opcodes.GETSTATIC)
                    AsmType.getType(prev.desc).objectClassName() else null
            is MethodInsnNode ->
                if (prev.name == "<init>" && prev.opcode == Opcodes.INVOKESPECIAL) prev.owner.replace('/', '.')
                else AsmType.getReturnType(prev.desc).objectClassName()
            else -> null
        }
        val resolved = fqn?.takeUnless { isNoise(it) } ?: run {
            onWarn("messaging.producer: skipping ${clazz.name}.${method.name}: payload type not recoverable")
            return null
        }
        return try {
            Class.forName(resolved, false, loader)
        } catch (t: Throwable) {
            onWarn("messaging.producer: skipping ${clazz.name}.${method.name}: cannot load $resolved: ${t.message}")
            null
        }
    }

    private fun AsmType.objectClassName(): String? = if (sort == AsmType.OBJECT) className else null

    private fun localType(method: MethodNode, varIndex: Int): String? {
        method.localVariables
            ?.firstOrNull { it.index == varIndex }
            ?.let { return AsmType.getType(it.desc).objectClassName() }
        // Fallback: parameter types from the method descriptor (slot 0 = `this` for instance methods).
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        val args = AsmType.getArgumentTypes(method.desc)
        val argIdx = if (isStatic) varIndex else varIndex - 1
        return args.getOrNull(argIdx)?.objectClassName()
    }

    private fun isNoise(fqn: String): Boolean =
        fqn.startsWith("java.") || fqn.startsWith("javax.") ||
            fqn.startsWith("jakarta.") || fqn.startsWith("kotlin.")

    private fun prevMeaningful(start: AbstractInsnNode?): AbstractInsnNode? {
        var n = start
        while (n != null && (n is LabelNode || n is LineNumberNode || n is FrameNode)) n = n.previous
        return n
    }

    private fun classResource(fqn: String): String = fqn.replace('.', '/') + ".class"

    private fun readClass(loader: ClassLoader, resource: String): ClassNode? {
        val stream = loader.getResourceAsStream(resource) ?: return null
        return stream.use {
            val reader = ClassReader(it)
            val node = ClassNode()
            reader.accept(node, ClassReader.SKIP_FRAMES)
            node
        }
    }
}
