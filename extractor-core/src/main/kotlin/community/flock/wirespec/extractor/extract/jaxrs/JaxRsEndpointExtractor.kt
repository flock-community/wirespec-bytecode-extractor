package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.ApiResponseExtractor
import community.flock.wirespec.extractor.extract.PathParser
import community.flock.wirespec.extractor.extract.ReturnTypeUnwrapper
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.extract.jaxrs.JaxRs.annotationNamed
import community.flock.wirespec.extractor.extract.jaxrs.JaxRs.stringAttr
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Param
import io.swagger.v3.oas.annotations.Operation
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method

/**
 * Extracts Wirespec endpoints from a JAX-RS resource class.
 *
 * Routing (path + HTTP method) is read from JAX-RS annotations (`@Path`,
 * `@GET`/`@POST`/…); every other facet — parameters, request body, responses,
 * referenced types, and the operation name — is driven by swagger/OpenAPI
 * annotations. The produced [Endpoint] model is identical to the Spring path's,
 * so it flows through the same AST builder, type ownership, and emitter.
 */
class JaxRsEndpointExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    private val params = JaxRsParamExtractor(types)
    private val apiResponses = ApiResponseExtractor(types, onWarn)

    fun extract(resourceClass: Class<*>): List<Endpoint> {
        val classPath = resourceClass.annotationNamed(JaxRs.PATH)?.stringAttr().orEmpty()
        return resourceClass.methods.flatMap { method -> extractFromMethod(resourceClass, classPath, method) }
    }

    private fun extractFromMethod(resourceClass: Class<*>, classPath: String, method: Method): List<Endpoint> {
        val httpMethodName = JaxRs.httpMethodOf(method) ?: return emptyList()
        val operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation::class.java)
        if (operation?.hidden == true) return emptyList()

        val httpMethod = httpMethodName.toHttpMethod() ?: run {
            onWarn("Skipping unsupported JAX-RS HTTP method '$httpMethodName' on ${resourceClass.simpleName}#${method.name}")
            return emptyList()
        }

        val methodPath = method.annotationNamed(JaxRs.PATH)?.stringAttr().orEmpty()

        val allParams = params.extractParams(method)
        val pathParamTypes = allParams.filter { it.source == Param.Source.PATH }.associate { it.name to it.type }
        val body = params.extractRequestBody(method)
        val unwrapped = ReturnTypeUnwrapper.unwrap(method)
        val responses = apiResponses.extract(method, unwrapped)

        val name = operation?.operationId?.takeIf { it.isNotBlank() }?.let(::pascalCase)
            ?: pascalCase(method.name)

        return listOf(
            Endpoint(
                controllerSimpleName = resourceClass.simpleName,
                name = name,
                method = httpMethod,
                pathSegments = PathParser.parse(joinPath(classPath, methodPath), pathParamTypes),
                queryParams = allParams.filter { it.source == Param.Source.QUERY },
                headerParams = allParams.filter { it.source == Param.Source.HEADER },
                cookieParams = allParams.filter { it.source == Param.Source.COOKIE },
                requestBody = body,
                responses = responses,
            )
        )
    }

    private fun joinPath(a: String, b: String): String {
        val left = a.trim('/').takeIf { it.isNotBlank() }
        val right = b.trim('/').takeIf { it.isNotBlank() }
        return listOfNotNull(left, right).joinToString("/")
    }

    private fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)

    private fun String.toHttpMethod(): HttpMethod? = when (uppercase()) {
        "GET" -> HttpMethod.GET
        "POST" -> HttpMethod.POST
        "PUT" -> HttpMethod.PUT
        "PATCH" -> HttpMethod.PATCH
        "DELETE" -> HttpMethod.DELETE
        "OPTIONS" -> HttpMethod.OPTIONS
        "HEAD" -> HttpMethod.HEAD
        "TRACE" -> HttpMethod.TRACE
        else -> null
    }
}
