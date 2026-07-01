package community.flock.wirespec.extractor.scan

import io.github.classgraph.ClassGraph

/**
 * Scans for JAX-RS root resource classes: classes annotated with `@Path`
 * (either `jakarta.ws.rs.Path` or the legacy `javax.ws.rs.Path`).
 *
 * String-based annotation lookup means this returns an empty list — and the
 * whole JAX-RS extraction path no-ops — for projects that don't use JAX-RS.
 */
object JaxRsResourceScanner {

    private val PATH_ANNOTATIONS = listOf("jakarta.ws.rs.Path", "javax.ws.rs.Path")

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "springfox",
        "io.swagger",
        "org.apache",
    )

    /**
     * @param scanPackages packages to include in the scan; pass empty for "everything reachable".
     * @param basePackage  if non-null, additionally restrict results to classes whose FQN starts with this prefix.
     * @param onWarn       called with a message whenever a class is skipped due to a load error.
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
            return PATH_ANNOTATIONS
                .flatMap { result.getClassesWithAnnotation(it) }
                .distinctBy { it.name }
                // Accept concrete resource classes and resource interfaces (including Kotlin
                // `fun interface`s), which are an idiomatic way to declare JAX-RS resources.
                // Annotation types carrying `@Path` (e.g. custom meta-annotations) are excluded.
                .filter { ci -> !ci.isAnnotation && (ci.isInterface || (ci.isStandardClass && !ci.isAbstract)) }
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                .mapNotNull { ci ->
                    try { ci.loadClass() }
                    catch (t: Throwable) {
                        onWarn("Skipping ${ci.name}: ${t.message}")
                        null
                    }
                }
        }
    }
}
