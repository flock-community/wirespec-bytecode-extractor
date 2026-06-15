package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.WireType
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.parameters.RequestBody

/**
 * Reads a [WireType] body from springdoc/swagger `@Content` schema declarations.
 * Shared by request-body and response extraction.
 */
internal object SwaggerContent {

    /**
     * The body type declared by the first `@Content` entry, or null when none of
     * them carry a concrete schema. An `array.schema.implementation` yields a list;
     * otherwise `schema.implementation` is used directly. `Void.class` means
     * "no schema declared".
     */
    fun bodyFromContent(content: Array<Content>, types: TypeExtractor): WireType? {
        val first = content.firstOrNull() ?: return null

        val arrayImpl: Class<*> = first.array.schema.implementation.java
        if (arrayImpl != Void::class.java) {
            return WireType.ListOf(types.extract(arrayImpl))
        }
        val impl: Class<*> = first.schema.implementation.java
        if (impl != Void::class.java) {
            return types.extract(impl)
        }
        return null
    }

    /** Body declared by a swagger `@RequestBody`'s content, or null. */
    fun bodyFromRequestBody(body: RequestBody?, types: TypeExtractor): WireType? =
        body?.let { bodyFromContent(it.content, types) }
}
