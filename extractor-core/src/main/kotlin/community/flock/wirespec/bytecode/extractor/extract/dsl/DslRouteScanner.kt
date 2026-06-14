package community.flock.wirespec.bytecode.extractor.extract.dsl

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo

/**
 * Finds classes that declare at least one method returning a Spring
 * `RouterFunction<...>` — the typical shape of a `@Bean` definition or any
 * factory that builds a functional routing tree.
 */
internal object DslRouteScanner {

    private val ROUTER_FUNCTION_TYPES = setOf(
        "org.springframework.web.reactive.function.server.RouterFunction",
        "org.springframework.web.servlet.function.RouterFunction",
    )

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "springfox",
        "io.swagger",
        "org.apache",
    )

    /**
     * @param scanPackages packages to include; pass empty for everything reachable from [classLoader].
     * @param basePackage  if non-null, additionally restrict to classes under this prefix.
     * @param onWarn       called when a candidate class fails to load.
     */
    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        onWarn: (String) -> Unit = {},
    ): List<Class<*>> {
        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            return result.allClasses
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                .filter { ci -> ci.declaresRouterFunction() }
                .mapNotNull { ci ->
                    try { ci.loadClass() }
                    catch (t: Throwable) {
                        onWarn("Skipping DSL candidate ${ci.name}: ${t.message}")
                        null
                    }
                }
        }
    }

    private fun ClassInfo.declaresRouterFunction(): Boolean =
        methodInfo.any { m ->
            val sig = m.typeSignatureOrTypeDescriptor ?: return@any false
            val ret = sig.resultType?.toString() ?: return@any false
            ROUTER_FUNCTION_TYPES.any { ret.startsWith(it) }
        }
}
