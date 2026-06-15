package community.flock.wirespec.extractor.extract

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.lang.reflect.AnnotatedElement

object JacksonNames {

    fun effectiveName(element: AnnotatedElement, original: String): String =
        element.getAnnotation(JsonProperty::class.java)?.value
            ?.takeIf { it.isNotEmpty() && it != JsonProperty.USE_DEFAULT_NAME }
            ?: original

    fun isIgnored(element: AnnotatedElement): Boolean =
        element.isAnnotationPresent(JsonIgnore::class.java)
}
