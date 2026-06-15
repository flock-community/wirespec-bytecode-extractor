// src/main/kotlin/community/flock/wirespec/extractor/extract/ReturnTypeUnwrapper.kt
package community.flock.wirespec.extractor.extract

import org.springframework.web.bind.annotation.ResponseStatus
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

object ReturnTypeUnwrapper {

    private val WRAPPERS = setOf(
        "org.springframework.http.ResponseEntity",
        "java.util.Optional",
        "reactor.core.publisher.Mono",
        "java.util.concurrent.Callable",
        "org.springframework.web.context.request.async.DeferredResult",
    )
    private const val FLUX = "reactor.core.publisher.Flux"
    private const val CONTINUATION = "kotlin.coroutines.Continuation"
    private const val KOTLIN_UNIT = "kotlin.Unit"

    /** A flattened view of a method's effective response payload. */
    data class Unwrapped(val type: Type, val isList: Boolean, val isVoid: Boolean)

    /**
     * Method-aware overload: detects Kotlin `suspend` functions (whose compiled
     * signature has a trailing `Continuation<? super T>` parameter and an erased
     * `Object` return type) and uses the Continuation's type argument as the
     * effective response type. Falls through to the [Type] overload for plain
     * non-suspend methods.
     */
    fun unwrap(method: Method): Unwrapped {
        val effective = continuationReturnType(method) ?: method.genericReturnType
        return unwrap(effective)
    }

    /**
     * If [method]'s last parameter is `Continuation<? super T>`, return `T`
     * (the actual suspend return type). Otherwise return null.
     */
    private fun continuationReturnType(method: Method): Type? {
        val params = method.genericParameterTypes
        if (params.isEmpty()) return null
        val last = params.last() as? ParameterizedType ?: return null
        val raw = last.rawType as? Class<*> ?: return null
        if (raw.name != CONTINUATION) return null
        val arg = last.actualTypeArguments.firstOrNull() ?: return Any::class.java
        return when (arg) {
            // `Continuation<? super T>` — T is the lower bound.
            is WildcardType -> arg.lowerBounds.firstOrNull()
                ?: arg.upperBounds.firstOrNull()
                ?: Any::class.java
            else -> arg
        }
    }

    fun unwrap(returnType: Type): Unwrapped {
        var current = returnType
        var isList = false

        while (true) {
            val rawName = (current as? ParameterizedType)?.rawType?.typeName
                ?: (current as? Class<*>)?.name

            if (rawName == FLUX && current is ParameterizedType) {
                isList = true
                current = current.actualTypeArguments[0]
                continue
            }
            if (rawName in WRAPPERS && current is ParameterizedType) {
                current = current.actualTypeArguments[0]
                continue
            }
            break
        }

        val isVoid = when (current) {
            is Class<*> -> current == Void.TYPE || current == Void::class.java || current.name == KOTLIN_UNIT
            else        -> current.typeName == "java.lang.Void" || current.typeName == KOTLIN_UNIT
        }

        return Unwrapped(current, isList = isList, isVoid = isVoid)
    }

    fun statusCodeFor(method: Method, unwrapped: Unwrapped): Int {
        val rs = method.getAnnotation(ResponseStatus::class.java)
        if (rs != null) {
            val codeAttr = rs.code
            val valueAttr = rs.value
            // Spring treats the two as aliases; pick whichever isn't the default.
            val code = if (codeAttr.value() != 500) codeAttr else valueAttr
            return code.value()
        }
        return if (unwrapped.isVoid) 204 else 200
    }
}
