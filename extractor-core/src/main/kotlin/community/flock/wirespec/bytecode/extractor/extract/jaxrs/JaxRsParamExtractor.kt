package community.flock.wirespec.bytecode.extractor.extract.jaxrs

import community.flock.wirespec.bytecode.extractor.extract.NullabilityResolver
import community.flock.wirespec.bytecode.extractor.extract.TypeExtractor
import community.flock.wirespec.bytecode.extractor.extract.jaxrs.JaxRs.annotationNamed
import community.flock.wirespec.bytecode.extractor.extract.jaxrs.JaxRs.hasAnnotation
import community.flock.wirespec.bytecode.extractor.extract.jaxrs.JaxRs.stringAttr
import community.flock.wirespec.bytecode.extractor.model.Param
import community.flock.wirespec.bytecode.extractor.model.Param.Source
import community.flock.wirespec.bytecode.extractor.model.WireType
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.parameters.RequestBody
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import io.swagger.v3.oas.annotations.Parameter as SwaggerParameter

/**
 * Extracts query/header/cookie/path parameters and the request entity body
 * from a JAX-RS resource method.
 *
 * Binding source and name come from JAX-RS annotations (`@PathParam`,
 * `@QueryParam`, `@HeaderParam`, `@CookieParam`) — or, where a parameter is
 * declared only with swagger's `@Parameter(in = …)`, from that. All OpenAPI
 * detail (required flag, body schema) is taken from the swagger annotations.
 */
class JaxRsParamExtractor(private val types: TypeExtractor) {

    fun extractParams(method: Method): List<Param> = method.parameters.mapNotNull(::toParam)

    /**
     * The request entity body: a swagger `@RequestBody`-annotated parameter, or the
     * lone JAX-RS entity parameter (one with no binding/injected annotation). The body
     * type prefers an explicit `@RequestBody` content schema, else the parameter's type.
     */
    fun extractRequestBody(method: Method): WireType? {
        val p = method.parameters.firstOrNull(::isEntityBody) ?: return null
        val swaggerBody = AnnotatedElementUtils.findMergedAnnotation(p, RequestBody::class.java)
        SwaggerContent.bodyFromRequestBody(swaggerBody, types)?.let { return it }
        val required = swaggerBody?.required ?: true
        val nullable = NullabilityResolver.isParameterNullable(p, springOptional = !required)
        return types.extract(p.parameterizedType, nullable)
    }

    private fun toParam(p: Parameter): Param? {
        p.annotationNamed(JaxRs.PATH_PARAM)?.let { a ->
            return param(a.stringAttr() ?: p.name, Source.PATH, p, requiredByDefault = true)
        }
        p.annotationNamed(JaxRs.QUERY_PARAM)?.let { a ->
            return param(a.stringAttr() ?: p.name, Source.QUERY, p, requiredByDefault = false)
        }
        p.annotationNamed(JaxRs.HEADER_PARAM)?.let { a ->
            return param(a.stringAttr() ?: p.name, Source.HEADER, p, requiredByDefault = false)
        }
        p.annotationNamed(JaxRs.COOKIE_PARAM)?.let { a ->
            return param(a.stringAttr() ?: p.name, Source.COOKIE, p, requiredByDefault = false)
        }
        // Declared via swagger only (no JAX-RS binding annotation): honour @Parameter(in = …).
        swaggerParam(p)?.let { sp ->
            val source = sp.`in`.toSource() ?: return null
            val name = sp.name.ifEmpty { p.name }
            return param(name, source, p, requiredByDefault = source == Source.PATH)
        }
        return null
    }

    private fun param(name: String, source: Source, p: Parameter, requiredByDefault: Boolean): Param {
        val required = swaggerParam(p)?.required ?: requiredByDefault
        val nullable = NullabilityResolver.isParameterNullable(p, springOptional = !required)
        return Param(name = name, source = source, type = types.extract(p.parameterizedType, nullable))
    }

    private fun swaggerParam(p: Parameter): SwaggerParameter? =
        AnnotatedElementUtils.findMergedAnnotation(p, SwaggerParameter::class.java)

    /**
     * A parameter is the request entity when it carries swagger's `@RequestBody`,
     * or when it has no JAX-RS binding/injected annotation and no swagger `@Parameter`
     * (a plain, unannotated JAX-RS method argument is the entity body).
     */
    private fun isEntityBody(p: Parameter): Boolean {
        if (AnnotatedElementUtils.isAnnotated(p, RequestBody::class.java.name)) return true
        if (p.hasAnnotation(JaxRs.INJECTED)) return false
        if (swaggerParam(p) != null) return false
        return true
    }

    private fun ParameterIn.toSource(): Source? = when (this) {
        ParameterIn.PATH -> Source.PATH
        ParameterIn.QUERY -> Source.QUERY
        ParameterIn.HEADER -> Source.HEADER
        ParameterIn.COOKIE -> Source.COOKIE
        ParameterIn.DEFAULT -> Source.QUERY
    }
}
