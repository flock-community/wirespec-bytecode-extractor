package community.flock.wirespec.spring.extractor.extract.kafka

import io.github.classgraph.ClassGraph
import java.lang.reflect.Method

/**
 * Discovers `@KafkaListener` listener methods in the user's compiled classes.
 *
 * Covers method-level `@KafkaListener`. Class-level `@KafkaListener` +
 * `@KafkaHandler` discovery is added in a follow-up.
 *
 * String-based class-name lookup: Spring Kafka is intentionally NOT on this
 * module's main classpath, so the scanner cleanly returns an empty list when
 * `org.springframework.kafka.*` is absent from [classLoader].
 */
internal object KafkaListenerScanner {

    private const val KAFKA_LISTENER_ANNOTATION = "org.springframework.kafka.annotation.KafkaListener"
    private const val KAFKA_HANDLER_ANNOTATION  = "org.springframework.kafka.annotation.KafkaHandler"

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
            val classes = result.getClassesWithMethodAnnotation(KAFKA_LISTENER_ANNOTATION)
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }

            for (ci in classes) {
                val cls = try { ci.loadClass() } catch (t: Throwable) {
                    onWarn("kafka.consumer: skipping ${ci.name}: ${t.message}")
                    continue
                }
                for (mi in ci.methodInfo) {
                    if (!mi.hasAnnotation(KAFKA_LISTENER_ANNOTATION)) continue
                    val method = cls.declaredMethods.firstOrNull {
                        it.name == mi.name && it.parameterCount == mi.parameterInfo.size
                    } ?: continue
                    methodSites += Site(cls, method)
                }
            }
            return methodSites
        }
    }
}
