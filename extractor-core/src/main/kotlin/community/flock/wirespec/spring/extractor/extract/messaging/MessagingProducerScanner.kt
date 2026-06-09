package community.flock.wirespec.spring.extractor.extract.messaging

import io.github.classgraph.ClassGraph
import java.lang.reflect.ParameterizedType

/**
 * Finds classes holding a producer-template field for a given [MessagingBroker].
 *
 * For generic templates (Kafka, Pulsar) the concrete value type is recovered
 * from the field's generic signature at `valueArgIndex`. For non-generic
 * templates (JMS, Rabbit) only the field name is recorded; the value type is
 * resolved per send call site by [MessagingProducerWalker].
 *
 * Discovery is class-name-string based: returns empty when the template type is
 * absent from [classLoader], or when the broker is listener-only (`producer == null`).
 */
internal object MessagingProducerScanner {

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "org.apache",
    )

    /**
     * @property valueClass concrete value type for generic templates; null for
     *   non-generic templates (resolved at the send call site).
     */
    data class TemplateField(
        val ownerClass: Class<*>,
        val fieldName: String,
        val valueClass: Class<*>?,
    )

    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        broker: MessagingBroker,
        onWarn: (String) -> Unit = {},
    ): List<TemplateField> {
        val producer = broker.producer ?: return emptyList()
        val templateFqn = when (producer) {
            is ProducerSpec.GenericTemplate    -> producer.fqn
            is ProducerSpec.NonGenericTemplate -> producer.fqn
        }
        val genericValueIndex = (producer as? ProducerSpec.GenericTemplate)?.valueArgIndex

        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableFieldInfo()
            .ignoreFieldVisibility()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            val out = mutableListOf<TemplateField>()
            for (ci in result.allClasses) {
                if (FRAMEWORK_EXCLUSIONS.any { ci.name.startsWith("$it.") }) continue
                if (basePackage != null && !(ci.name.startsWith("$basePackage.") || ci.name == basePackage)) continue
                if (ci.fieldInfo.none { it.typeDescriptor?.toString() == templateFqn }) continue

                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("messaging.${broker.id}.producer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (field in cls.declaredFields) {
                    if (field.type.name != templateFqn) continue
                    if (genericValueIndex == null) {
                        out += TemplateField(cls, field.name, null)
                    } else {
                        val v = (field.genericType as? ParameterizedType)
                            ?.actualTypeArguments
                            ?.getOrNull(genericValueIndex) as? Class<*>
                        if (v == null || v == Any::class.java || v == java.lang.Object::class.java) {
                            onWarn("messaging.${broker.id}.producer: skipping ${cls.name}.${field.name}: template value type unresolved")
                            continue
                        }
                        out += TemplateField(cls, field.name, v)
                    }
                }
            }
            return out
        }
    }
}
