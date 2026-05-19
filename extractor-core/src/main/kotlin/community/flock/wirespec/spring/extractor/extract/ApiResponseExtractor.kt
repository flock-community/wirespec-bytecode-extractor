// src/main/kotlin/community/flock/wirespec/spring/extractor/extract/ApiResponseExtractor.kt
package community.flock.wirespec.spring.extractor.extract

import community.flock.wirespec.spring.extractor.model.Endpoint
import community.flock.wirespec.spring.extractor.model.WireType
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method

/**
 * Reads springdoc `@ApiResponses` / `@ApiResponse` declarations on a handler
 * method and produces one [Endpoint.Response] per declared status.
 *
 * Falls back to a single response built from the method signature when no
 * such annotations are present.
 */
class ApiResponseExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    fun extract(method: Method, unwrapped: ReturnTypeUnwrapper.Unwrapped): List<Endpoint.Response> {
        val declared = collectApiResponses(method)
        val naturalStatus = ReturnTypeUnwrapper.statusCodeFor(method, unwrapped)
        val naturalBody = methodBody(unwrapped)

        if (declared.isEmpty()) {
            return listOf(Endpoint.Response(naturalStatus, naturalBody))
        }

        val out = mutableListOf<Endpoint.Response>()
        for (ann in declared) {
            val status = ann.responseCode.toIntOrNull()
            if (status == null) {
                onWarn(
                    "Skipping non-numeric @ApiResponse.responseCode '${ann.responseCode}' " +
                        "on ${method.declaringClass.simpleName}#${method.name}"
                )
                continue
            }
            val body = bodyFromContent(ann)
                ?: if (status == naturalStatus) naturalBody else null
            out += Endpoint.Response(status, body)
        }
        // Defensive: if every entry was skipped, fall back to the natural response so the
        // endpoint still has at least one response.
        return out.ifEmpty { listOf(Endpoint.Response(naturalStatus, naturalBody)) }
    }

    private fun collectApiResponses(method: Method): List<ApiResponse> {
        // Use AnnotatedElementUtils so meta-annotated and inherited variants are found.
        val container = AnnotatedElementUtils.findMergedAnnotation(method, ApiResponses::class.java)
        val containerEntries = container?.value?.toList().orEmpty()
        val standalone = AnnotatedElementUtils.findAllMergedAnnotations(method, ApiResponse::class.java).toList()
        // Container takes precedence; merge any standalone entries that aren't already covered by status code.
        val seen = containerEntries.map { it.responseCode }.toMutableSet()
        val merged = containerEntries.toMutableList()
        for (a in standalone) {
            if (seen.add(a.responseCode)) merged += a
        }
        return merged
    }

    private fun methodBody(unwrapped: ReturnTypeUnwrapper.Unwrapped): WireType? {
        if (unwrapped.isVoid) return null
        val raw = types.extract(unwrapped.type)
        return if (unwrapped.isList) WireType.ListOf(raw) else raw
    }

    /**
     * Reads the first @Content entry's schema. If it carries an `array.schema.implementation`,
     * the body is a list; otherwise `schema.implementation` is used directly. `Void.class`
     * is treated as "no schema declared".
     */
    private fun bodyFromContent(ann: ApiResponse): WireType? {
        val content = ann.content.firstOrNull() ?: return null

        val arrayImpl: Class<*> = content.array.schema.implementation.java
        if (arrayImpl != Void::class.java) {
            return WireType.ListOf(types.extract(arrayImpl))
        }
        val impl: Class<*> = content.schema.implementation.java
        if (impl != Void::class.java) {
            return types.extract(impl)
        }
        return null
    }
}
