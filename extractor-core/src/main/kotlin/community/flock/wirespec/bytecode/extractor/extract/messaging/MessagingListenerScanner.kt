package community.flock.wirespec.bytecode.extractor.extract.messaging

import io.github.classgraph.ClassGraph
import java.lang.reflect.Method

/**
 * Discovers listener methods for a given [MessagingBroker]: method-level
 * `broker.listenerAnnotation`, plus class-level annotation + `broker.handlerAnnotation`
 * methods when the broker supports a multi-handler form (Kafka, Rabbit).
 *
 * String-based annotation lookup: returns an empty list when the broker's
 * annotations are absent from [classLoader].
 */
internal object MessagingListenerScanner {

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "org.apache",
    )

    /** A single listener method to extract a channel from. */
    data class Site(val ownerClass: Class<*>, val method: Method)

    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        broker: MessagingBroker,
        onWarn: (String) -> Unit = {},
    ): List<Site> {
        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            val methodSites = mutableListOf<Site>()

            val classes = result.getClassesWithMethodAnnotation(broker.listenerAnnotation)
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
            for (ci in classes) {
                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("messaging.${broker.id}.consumer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (mi in ci.methodInfo) {
                    if (!mi.hasAnnotation(broker.listenerAnnotation)) continue
                    val method = cls.declaredMethods.firstOrNull {
                        it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                    } ?: continue
                    methodSites += Site(cls, method)
                }
            }

            val handler = broker.handlerAnnotation
            if (handler != null) {
                val classLevel = result.getClassesWithAnnotation(broker.listenerAnnotation)
                    .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                    .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                for (ci in classLevel) {
                    val cls = try { ci.loadClass() } catch (t: Throwable) {
                        onWarn("messaging.${broker.id}.consumer: skipping ${ci.name}: ${t.message}")
                        continue
                    }
                    for (mi in ci.methodInfo) {
                        if (!mi.hasAnnotation(handler)) continue
                        val method = cls.declaredMethods.firstOrNull {
                            it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                        } ?: continue
                        methodSites += Site(cls, method)
                    }
                }
            }
            return methodSites
        }
    }
}
