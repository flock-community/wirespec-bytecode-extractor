// src/main/kotlin/community/flock/wirespec/spring/extractor/extract/ParamExtractor.kt
package community.flock.wirespec.spring.extractor.extract

import community.flock.wirespec.spring.extractor.model.Param
import community.flock.wirespec.spring.extractor.model.Param.Source
import community.flock.wirespec.spring.extractor.model.WireType
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ValueConstants
import java.lang.reflect.Method
import java.lang.reflect.Parameter

class ParamExtractor(private val types: TypeExtractor) {

    fun extractParams(method: Method): List<Param> = method.parameters.mapNotNull(::toParam)

    fun extractRequestBody(method: Method): WireType? {
        val p = method.parameters.firstOrNull {
            AnnotatedElementUtils.isAnnotated(it, RequestBody::class.java.name)
        } ?: return null
        val required = AnnotatedElementUtils.findMergedAnnotation(p, RequestBody::class.java)?.required ?: true
        val nullable = NullabilityResolver.isParameterNullable(p, springOptional = !required)
        return types.extract(p.parameterizedType, nullable)
    }

    private fun toParam(p: Parameter): Param? {
        // Only extract the parameter's type once we've confirmed it's actually a Spring
        // binding parameter — otherwise we'd pollute TypeExtractor.definitions with
        // synthetic / framework parameters (notably Kotlin's `Continuation<? super T>`,
        // which would otherwise leak Continuation and CoroutineContext into the schema).
        AnnotatedElementUtils.findMergedAnnotation(p, PathVariable::class.java)?.let { a ->
            // Path variables are part of the URL: Spring treats them as required.
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.PATH, p, springOptional = !a.required)
        }
        AnnotatedElementUtils.findMergedAnnotation(p, RequestParam::class.java)?.let { a ->
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.QUERY, p, springOptional = a.isOptional())
        }
        AnnotatedElementUtils.findMergedAnnotation(p, RequestHeader::class.java)?.let { a ->
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.HEADER, p, springOptional = a.isOptional())
        }
        AnnotatedElementUtils.findMergedAnnotation(p, CookieValue::class.java)?.let { a ->
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.COOKIE, p, springOptional = a.isOptional())
        }
        return null
    }

    private fun param(name: String, source: Source, p: Parameter, springOptional: Boolean): Param {
        val nullable = NullabilityResolver.isParameterNullable(p, springOptional)
        return Param(name = name, source = source, type = types.extract(p.parameterizedType, nullable))
    }

    /** A param is optional to Spring when it's declared `required = false` or carries a `defaultValue`. */
    private fun RequestParam.isOptional(): Boolean = !required || defaultValue != ValueConstants.DEFAULT_NONE
    private fun RequestHeader.isOptional(): Boolean = !required || defaultValue != ValueConstants.DEFAULT_NONE
    private fun CookieValue.isOptional(): Boolean = !required || defaultValue != ValueConstants.DEFAULT_NONE
}
