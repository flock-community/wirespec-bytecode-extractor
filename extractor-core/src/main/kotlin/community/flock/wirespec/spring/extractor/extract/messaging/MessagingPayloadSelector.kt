package community.flock.wirespec.spring.extractor.extract.messaging

import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Picks the payload parameter of a listener method and unwraps framework
 * wrappers down to the value type. The shared rules (Spring `@Payload`/`@Header`,
 * `Message<T>`, `List<T>`) apply to every broker; broker-specific record
 * wrappers and untyped meta types come from the [MessagingBroker] descriptor.
 *
 * Broker libraries are not on the main classpath: meta/wrapper types are matched
 * by string FQN against the parameter's `Class.name`.
 */
internal object MessagingPayloadSelector {

    private const val PAYLOAD_ANNOTATION = "org.springframework.messaging.handler.annotation.Payload"
    private const val HEADER_ANNOTATION  = "org.springframework.messaging.handler.annotation.Header"
    private const val HEADERS_ANNOTATION = "org.springframework.messaging.handler.annotation.Headers"

    private const val MESSAGE_FQN = "org.springframework.messaging.Message"
    private const val LIST_FQN    = "java.util.List"

    sealed interface Result {
        /** Payload param picked; [payloadType] is post-unwrap. */
        data class Selected(val payloadType: Type) : Result
        data class Skipped(val reason: String) : Result
    }

    fun select(method: Method, broker: MessagingBroker): Result {
        val params = method.parameters.toList()
        if (params.isEmpty()) return Result.Skipped("no parameters")

        // 1. @Payload wins.
        val payloadAnnotated = params.firstOrNull { p ->
            p.annotations.any { it.annotationClass.java.name == PAYLOAD_ANNOTATION }
        }
        if (payloadAnnotated != null) return unwrap(payloadAnnotated.parameterizedType, broker)

        // 2. Exactly one non-meta parameter.
        val nonMeta = params.filter { !isMeta(it, broker) }
        return when (nonMeta.size) {
            1    -> unwrap(nonMeta.single().parameterizedType, broker)
            0    -> Result.Skipped("no payload parameter")
            else -> Result.Skipped("ambiguous payload parameter")
        }
    }

    private fun isMeta(p: Parameter, broker: MessagingBroker): Boolean {
        if (p.annotations.any {
                val n = it.annotationClass.java.name
                n == HEADER_ANNOTATION || n == HEADERS_ANNOTATION
            }) return true
        val raw = (p.parameterizedType as? Class<*>)
            ?: (p.parameterizedType as? ParameterizedType)?.rawType as? Class<*>
        val name = raw?.name ?: return false
        return name in broker.rawMetaTypes
    }

    private fun unwrap(t: Type, broker: MessagingBroker): Result {
        if (t is WildcardType) return Result.Skipped("wildcard payload")
        if (t is Class<*>) {
            // Raw (un-parameterized) wrapper → value type unrecoverable.
            if (t.name == MESSAGE_FQN || t.name == LIST_FQN || broker.recordWrappers.any { it.fqn == t.name }) {
                return Result.Skipped("raw ${t.simpleName} payload")
            }
            return Result.Selected(t)
        }
        if (t is ParameterizedType) {
            val raw = (t.rawType as? Class<*>) ?: return Result.Skipped("unrecognised payload type")
            broker.recordWrappers.firstOrNull { it.fqn == raw.name }?.let { w ->
                val arg = t.actualTypeArguments.getOrNull(w.valueArgIndex)
                    ?: return Result.Skipped("${raw.simpleName} without value type")
                return unwrap(arg, broker)
            }
            return when (raw.name) {
                MESSAGE_FQN -> unwrap(t.actualTypeArguments.getOrNull(0) ?: return Result.Skipped("Message without T"), broker)
                LIST_FQN    -> unwrap(t.actualTypeArguments.getOrNull(0) ?: return Result.Skipped("List without T"), broker)
                else        -> Result.Selected(t)
            }
        }
        return Result.Skipped("unrecognised payload type ${t.typeName}")
    }
}
