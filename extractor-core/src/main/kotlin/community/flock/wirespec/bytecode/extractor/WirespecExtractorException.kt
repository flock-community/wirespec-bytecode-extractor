package community.flock.wirespec.bytecode.extractor

/**
 * Thrown by [WirespecExtractor.extract] when extraction fails because of:
 * - a missing or empty classes directory
 * - a non-writable output directory
 * - two scanned controllers sharing a simple name
 * - a generic type the extractor cannot flatten (raw, wildcard, unbound, raw superclass)
 *
 * The companion factory methods produce the user-facing messages for the
 * generic-flattening cases. See `docs/superpowers/specs/2026-05-13-flatten-generics-design.md`.
 */
open class WirespecExtractorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        fun rawGenericReference(rawClass: String, controllerMethod: String): WirespecExtractorException =
            WirespecExtractorException(
                "Cannot extract raw generic type ${simpleName(rawClass)} " +
                    "(declared with type parameters) reached from $controllerMethod; " +
                    "provide a concrete type argument."
            )

        fun wildcardArgument(atType: String, controllerMethod: String): WirespecExtractorException =
            WirespecExtractorException(
                "Wildcard type argument in $atType reached from $controllerMethod " +
                    "cannot be flattened; replace with a concrete type."
            )

        fun unboundTypeVariable(
            variable: String,
            inClassField: String,
            controllerMethod: String,
        ): WirespecExtractorException =
            WirespecExtractorException(
                "Type variable $variable in $inClassField is not bound at the reference " +
                    "reached from $controllerMethod; this indicates an extractor bug — please report."
            )

        fun rawGenericSuperclass(subclassName: String, rawSuperclassName: String): WirespecExtractorException =
            WirespecExtractorException(
                "Class $subclassName extends generic $rawSuperclassName without type arguments; " +
                    "provide a parameterized superclass like $rawSuperclassName<UserDto>."
            )

        private fun simpleName(fqn: String): String = fqn.substringAfterLast('.')
    }
}
