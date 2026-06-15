package community.flock.wirespec.extractor.extract.jaxrs

import java.lang.reflect.AnnotatedElement

/**
 * Reflective access to JAX-RS annotations.
 *
 * JAX-RS types are read by fully-qualified name rather than against compiled
 * annotation classes so the extractor:
 *
 * 1. supports both the `jakarta.ws.rs` (Jakarta EE 9+) and the legacy
 *    `javax.ws.rs` namespaces from a single code path, and
 * 2. carries no compile-time dependency on a JAX-RS API on its main classpath —
 *    extraction simply finds nothing (and no-ops) in projects that don't use it.
 *
 * Mirrors the string-based lookup philosophy already used by the messaging
 * scanners.
 */
internal object JaxRs {

    val PATH = ns("Path")
    val PATH_PARAM = ns("PathParam")
    val QUERY_PARAM = ns("QueryParam")
    val HEADER_PARAM = ns("HeaderParam")
    val COOKIE_PARAM = ns("CookieParam")
    val MATRIX_PARAM = ns("MatrixParam")
    val FORM_PARAM = ns("FormParam")
    val BEAN_PARAM = ns("BeanParam")
    val CONTEXT = ns("core.Context")
    val SUSPENDED = ns("container.Suspended")
    private val HTTP_METHOD = ns("HttpMethod")

    /** Every JAX-RS annotation that binds a method parameter to a non-entity source. */
    val PARAM_BINDINGS: List<String> =
        PATH_PARAM + QUERY_PARAM + HEADER_PARAM + COOKIE_PARAM + MATRIX_PARAM + FORM_PARAM

    /** Annotations marking a parameter as framework-injected rather than the request entity. */
    val INJECTED: List<String> = PARAM_BINDINGS + BEAN_PARAM + CONTEXT + SUSPENDED

    private fun ns(simpleName: String): List<String> =
        listOf("jakarta.ws.rs.$simpleName", "javax.ws.rs.$simpleName")

    /** First annotation on [this] whose type name is one of [names], or null. */
    fun AnnotatedElement.annotationNamed(names: List<String>): Annotation? =
        annotations.firstOrNull { it.annotationClass.java.name in names }

    fun AnnotatedElement.hasAnnotation(names: List<String>): Boolean =
        annotations.any { it.annotationClass.java.name in names }

    /** Read a String-valued attribute (defaults to `value`) off an annotation reflectively. */
    fun Annotation.stringAttr(attr: String = "value"): String? =
        try {
            (annotationClass.java.getMethod(attr).invoke(this) as? String)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

    /**
     * The HTTP method name (`GET`, `POST`, …) of a resource [method], discovered
     * via the JAX-RS `@HttpMethod` meta-annotation that marks `@GET`/`@POST`/…
     * (and any custom HTTP-method annotation). Returns null when [method] carries
     * no HTTP-method annotation — i.e. it is not a resource method.
     */
    fun httpMethodOf(method: AnnotatedElement): String? {
        for (a in method.annotations) {
            val meta = a.annotationClass.java.annotations.firstOrNull {
                it.annotationClass.java.name in HTTP_METHOD
            }
            if (meta != null) return meta.stringAttr()
        }
        return null
    }
}
