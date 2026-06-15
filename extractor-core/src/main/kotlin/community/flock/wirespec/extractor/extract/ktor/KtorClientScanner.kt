package community.flock.wirespec.extractor.extract.ktor

import io.github.classgraph.ClassGraph

/**
 * Finds classes that issue Ktor **client** requests — i.e. any class whose
 * bytecode references the inlined `HttpRequestBuilder` lifecycle or the
 * `HttpStatement` that fires a request.
 *
 * Like [KtorRoutingScanner], candidates are confirmed by a lightweight ASM pass
 * since Ktor client calls carry no marker annotation.
 */
internal object KtorClientScanner {

    private val MARKER_OWNERS = setOf(
        KtorBytecode.HTTP_REQUEST_BUILDER,
        KtorBytecode.HTTP_STATEMENT,
    )

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
                        onWarn("Skipping Ktor client candidate ${ci.name}: ${t.message}")
                        null
                    }
                }
        }
    }
}
