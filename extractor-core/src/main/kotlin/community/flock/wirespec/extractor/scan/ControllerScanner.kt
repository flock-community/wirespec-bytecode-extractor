package community.flock.wirespec.extractor.scan

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo

object ControllerScanner {

    private const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
    private const val CONTROLLER = "org.springframework.stereotype.Controller"
    private const val RESPONSE_BODY = "org.springframework.web.bind.annotation.ResponseBody"

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "springfox",
        "io.swagger",
        "org.apache",
    )

    /**
     * Scan [classLoader] for Spring controllers.
     *
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
            val direct = result.getClassesWithAnnotation(REST_CONTROLLER)
            val viaController = result.getClassesWithAnnotation(CONTROLLER)
                .filter { hasResponseBodyMethod(it) }

            return (direct + viaController)
                .distinctBy { it.name }
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

    private fun hasResponseBodyMethod(ci: ClassInfo): Boolean =
        ci.methodInfo.any { m -> m.hasAnnotation(RESPONSE_BODY) }
}
