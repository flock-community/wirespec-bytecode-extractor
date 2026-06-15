package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.WireType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.lang.reflect.AnnotatedElement

object ValidationConstraints {

    /**
     * If [element] carries Bean Validation constraints that refine [base],
     * return a [WireType.Refined] capturing them; otherwise return [base].
     * Refined types get a deterministic name derived from the constraint tuple
     * so that identical refinements always collapse to the same definition.
     */
    fun refine(element: AnnotatedElement, base: WireType): WireType {
        if (base !is WireType.Primitive) return base

        val pattern = element.getAnnotation(Pattern::class.java)?.regexp
        val size = element.getAnnotation(Size::class.java)
        val min = element.getAnnotation(Min::class.java)?.value?.toString()
        val max = element.getAnnotation(Max::class.java)?.value?.toString()

        val hasAny = pattern != null || size != null || min != null || max != null
        if (!hasAny) return base

        val resolvedMin = min ?: size?.min?.toString()
        val resolvedMax = max ?: size?.max?.toString()?.takeIf { size.max != Int.MAX_VALUE }

        return WireType.Refined(
            name = refinedName(base, pattern, resolvedMin, resolvedMax),
            base = base.copy(nullable = false),
            regex = pattern,
            min = resolvedMin,
            max = resolvedMax,
            nullable = base.nullable,
        )
    }

    private fun refinedName(base: WireType.Primitive, regex: String?, min: String?, max: String?): String {
        val key = "${base.kind}|${regex ?: ""}|${min ?: ""}|${max ?: ""}"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hex = md.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return "Refined" + hex.take(8).uppercase()
    }

    fun isRequired(element: AnnotatedElement): Boolean =
        element.isAnnotationPresent(NotNull::class.java) ||
        element.isAnnotationPresent(NotBlank::class.java)
}
