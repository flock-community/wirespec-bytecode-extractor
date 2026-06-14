package community.flock.wirespec.bytecode.extractor.extract.ktor

import io.github.classgraph.ClassGraph

/**
 * Finds classes that build a Ktor **server** routing tree — i.e. any class whose
 * bytecode invokes a member of `io.ktor.server.routing.RoutingBuilderKt` or
 * `RoutingRootKt` (`routing { }`, `route(...)`, `get/post/...`).
 *
 * Ktor routing carries no marker annotation or return type (unlike Spring's
 * `RouterFunction`), so candidates are confirmed by a lightweight ASM pass over
 * each in-scope class.
 */
internal object KtorRoutingScanner {

    private val MARKER_OWNERS = setOf(KtorBytecode.ROUTING_BUILDER, KtorBytecode.ROUTING_ROOT)

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "io.ktor", "kotlin", "kotlinx", "org.springframework", "org.apache",
    )

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

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            return result.allClasses
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                .filter { ci ->
                    val cn = KtorBytecode.readClass(classLoader, ci.name.replace('.', '/')) ?: return@filter false
                    KtorBytecode.referencesOwner(cn, MARKER_OWNERS)
                }
                .mapNotNull { ci ->
                    try { ci.loadClass() }
                    catch (t: Throwable) {
                        onWarn("Skipping Ktor routing candidate ${ci.name}: ${t.message}")
                        null
                    }
                }
        }
    }
}
