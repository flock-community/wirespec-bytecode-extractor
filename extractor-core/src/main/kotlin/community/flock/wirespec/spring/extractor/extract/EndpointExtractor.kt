// src/main/kotlin/community/flock/wirespec/spring/extractor/extract/EndpointExtractor.kt
package community.flock.wirespec.spring.extractor.extract

import community.flock.wirespec.spring.extractor.model.Endpoint
import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.spring.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.spring.extractor.model.Param
import community.flock.wirespec.spring.extractor.model.WireType
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import java.lang.reflect.Method

class EndpointExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    private val params = ParamExtractor(types)
    private val apiResponses = ApiResponseExtractor(types, onWarn)

    fun extract(controllerClass: Class<*>): List<Endpoint> {
        val classMapping = AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping::class.java)
        val classPaths = classMapping?.path?.toList()?.takeIf { it.isNotEmpty() } ?: listOf("")
        return controllerClass.methods.flatMap { method -> extractFromMethod(controllerClass, classPaths, method) }
    }

    private fun extractFromMethod(controllerClass: Class<*>, classPaths: List<String>, method: Method): List<Endpoint> {
        val mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
            ?: return emptyList()
        val methodPaths = mapping.path.toList().takeIf { it.isNotEmpty() } ?: listOf("")
        val httpMethods = if (mapping.method.isEmpty()) listOf(RequestMethod.GET) else mapping.method.toList()

        val allParams = params.extractParams(method)
        val body = params.extractRequestBody(method)
        val unwrapped = ReturnTypeUnwrapper.unwrap(method)
        val responses = apiResponses.extract(method, unwrapped)

        val needsMethodSuffix = httpMethods.size > 1
        val needsPathSuffix = methodPaths.size > 1

        return httpMethods.flatMap { rm ->
            classPaths.flatMap { cp ->
                methodPaths.mapIndexed { pathIdx, mp ->
                    val baseName = pascalCase(method.name)
                    val name = baseName +
                        (if (needsMethodSuffix) rm.name.lowercase().replaceFirstChar { it.uppercase() } else "") +
                        (if (needsPathSuffix) (pathIdx + 1).toString() else "")
                    Endpoint(
                        controllerSimpleName = controllerClass.simpleName,
                        name = name,
                        method = rm.toHttpMethod(),
                        pathSegments = parsePath(joinPath(cp, mp)),
                        queryParams = allParams.filter { it.source == Param.Source.QUERY },
                        headerParams = allParams.filter { it.source == Param.Source.HEADER },
                        cookieParams = allParams.filter { it.source == Param.Source.COOKIE },
                        requestBody = body,
                        responses = responses,
                    )
                }
            }
        }
    }

    private fun joinPath(a: String, b: String): String {
        val left = a.trim('/').takeIf { it.isNotBlank() }
        val right = b.trim('/').takeIf { it.isNotBlank() }
        return listOfNotNull(left, right).joinToString("/")
    }

    internal fun parsePath(path: String): List<PathSegment> =
        path.split('/').filter { it.isNotBlank() }.map { seg ->
            val match = Regex("""^\{([^:}]+)(?::[^}]+)?}$""").matchEntire(seg)
            if (match != null) PathSegment.Variable(match.groupValues[1], WireType.Primitive(WireType.Primitive.Kind.STRING))
            else PathSegment.Literal(seg)
        }

    internal fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)

    private fun RequestMethod.toHttpMethod(): HttpMethod = when (this) {
        RequestMethod.GET -> HttpMethod.GET
        RequestMethod.POST -> HttpMethod.POST
        RequestMethod.PUT -> HttpMethod.PUT
        RequestMethod.PATCH -> HttpMethod.PATCH
        RequestMethod.DELETE -> HttpMethod.DELETE
        RequestMethod.OPTIONS -> HttpMethod.OPTIONS
        RequestMethod.HEAD -> HttpMethod.HEAD
        RequestMethod.TRACE -> HttpMethod.TRACE
    }
}
