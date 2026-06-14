package community.flock.wirespec.bytecode.extractor.extract

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.AnnotatedType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.Optional
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction

object NullabilityResolver {

    /**
     * Returns true if the given member (a DTO field, record component, or bean
     * accessor) should be modelled as nullable in Wirespec.
     *
     * Priority order:
     *  1. Java primitive type → non-null
     *  2. Optional<T> → nullable
     *  3. Kotlin nullability metadata (kotlin.Metadata + member type)
     *  4. @Nullable / @NonNull annotations (JSR-305, JetBrains, JSpecify, …) — both
     *     declaration- and TYPE_USE-targeted
     *  5. @NotNull / @NotBlank / @Schema(required=true) → non-null
     *  6. JSpecify @NullMarked scope (class/enclosing class/package) → non-null
     *  7. Default → nullable
     */
    fun isNullable(element: AnnotatedElement, declaredJavaType: Class<*>): Boolean {
        if (declaredJavaType.isPrimitive) return false
        if (declaredJavaType == Optional::class.java) return true
        kotlinNullable(element)?.let { return it }
        annotationDeclaredNullable(element)?.let { return it }
        if (element.isAnnotationPresent(NotNull::class.java)) return false
        if (element.isAnnotationPresent(NotBlank::class.java)) return false
        if (element.getAnnotation(Schema::class.java)?.required == true) return false
        if (isNullMarked(element)) return false
        return true
    }

    /**
     * Returns true if a Spring binding parameter (`@PathVariable`,
     * `@RequestParam`, `@RequestHeader`, `@CookieValue`, `@RequestBody`) should be
     * modelled as nullable.
     *
     * Unlike DTO fields, a binding parameter defaults to **non-null**: Spring
     * resolves it as required unless told otherwise. [springOptional] captures
     * Spring's own view (`required = false` or a `defaultValue` present); an
     * explicit `@Nullable`, a Kotlin nullable type, or `Optional<T>` also make it
     * nullable and win over the required flag.
     *
     * Priority order:
     *  1. Java primitive type → non-null
     *  2. Optional<T> → nullable
     *  3. @Nullable / @NonNull annotations → as declared
     *  4. Kotlin nullable parameter type → as declared
     *  5. Spring `required = false` / `defaultValue` → nullable
     *  6. Default → non-null
     */
    fun isParameterNullable(parameter: Parameter, springOptional: Boolean): Boolean {
        if (parameter.type.isPrimitive) return false
        if (parameter.type == Optional::class.java) return true
        annotationDeclaredNullable(parameter)?.let { return it }
        kotlinParameterNullable(parameter)?.let { return it }
        if (springOptional) return true
        return false
    }

    fun schemaDescription(element: AnnotatedElement): String? =
        element.getAnnotation(Schema::class.java)?.description?.takeIf { it.isNotBlank() }

    /**
     * Read Kotlin's @Metadata to determine nullability for a property field.
     * Returns null when this isn't a Kotlin class member.
     */
    private fun kotlinNullable(element: AnnotatedElement): Boolean? {
        val field = element as? Field ?: return null
        val owner = field.declaringClass
        if (!owner.isAnnotationPresent(Metadata::class.java)) return null
        val kClass = try { owner.kotlin } catch (_: Throwable) { return null }
        val prop = kClass.members.firstOrNull { it.name == field.name } ?: return null
        return prop.returnType.isMarkedNullable
    }

    /**
     * Read Kotlin's @Metadata to determine nullability for a method parameter.
     * Java parameters of an instance method align by index with the Kotlin
     * function's value parameters (both exclude the dispatch receiver), so we
     * match positionally. Returns null when this isn't a Kotlin method.
     */
    private fun kotlinParameterNullable(parameter: Parameter): Boolean? {
        val method = parameter.declaringExecutable as? Method ?: return null
        if (!method.declaringClass.isAnnotationPresent(Metadata::class.java)) return null
        val function = try { method.kotlinFunction } catch (_: Throwable) { return null } ?: return null
        val index = method.parameters.indexOf(parameter)
        if (index < 0) return null
        val kParam = function.valueParameters.getOrNull(index) ?: return null
        return kParam.type.isMarkedNullable
    }

    private val NULLABLE_FQNS = setOf(
        "javax.annotation.Nullable",
        "jakarta.annotation.Nullable",
        "org.jetbrains.annotations.Nullable",
        "org.springframework.lang.Nullable",
        "androidx.annotation.Nullable",
        "edu.umd.cs.findbugs.annotations.Nullable",
        "org.jspecify.annotations.Nullable",
        "org.checkerframework.checker.nullness.qual.Nullable",
    )

    private val NON_NULL_FQNS = setOf(
        "javax.annotation.Nonnull",
        "jakarta.annotation.Nonnull",
        "org.jetbrains.annotations.NotNull",
        "org.springframework.lang.NonNull",
        "androidx.annotation.NonNull",
        "edu.umd.cs.findbugs.annotations.NonNull",
        "org.jspecify.annotations.NonNull",
        "org.checkerframework.checker.nullness.qual.NonNull",
    )

    private const val NULL_MARKED_FQN = "org.jspecify.annotations.NullMarked"
    private const val NULL_UNMARKED_FQN = "org.jspecify.annotations.NullUnmarked"

    private fun annotationDeclaredNullable(element: AnnotatedElement): Boolean? {
        val fqns = nullnessAnnotationFqns(element)
        return when {
            fqns.any { it in NULLABLE_FQNS } -> true
            fqns.any { it in NON_NULL_FQNS } -> false
            else -> null
        }
    }

    /**
     * Collect the fully-qualified names of every annotation visible on [element],
     * covering both declaration-targeted annotations (`element.annotations`) and
     * TYPE_USE-targeted ones (`element.annotatedType.annotations`). JSpecify's
     * `@Nullable` / `@NonNull` are TYPE_USE-only, so they are invisible to the
     * declaration view and must be read off the annotated type.
     */
    private fun nullnessAnnotationFqns(element: AnnotatedElement): Set<String> {
        val result = LinkedHashSet<String>()
        element.annotations.forEach { result += it.annotationClass.java.name }
        typeUseAnnotatedType(element)?.annotations?.forEach { result += it.annotationClass.java.name }
        return result
    }

    private fun typeUseAnnotatedType(element: AnnotatedElement): AnnotatedType? = when (element) {
        is Field     -> element.annotatedType
        is Parameter -> element.annotatedType
        is Method    -> element.annotatedReturnType
        else         -> null
    }

    /**
     * Whether [element] sits inside a JSpecify `@NullMarked` scope, meaning
     * unannotated references default to non-null. The nearest enclosing scope
     * wins: the element itself, then enclosing classes (leaf-first), then the
     * package. `@NullUnmarked` flips the default back off.
     */
    private fun isNullMarked(element: AnnotatedElement): Boolean {
        scopeMark(element)?.let { return it }
        var cls: Class<*>? = owningClass(element)
        while (cls != null) {
            scopeMark(cls)?.let { return it }
            cls = cls.enclosingClass
        }
        owningClass(element)?.`package`?.let { pkg -> scopeMark(pkg)?.let { return it } }
        return false
    }

    private fun owningClass(element: AnnotatedElement): Class<*>? = when (element) {
        is Class<*>  -> element
        is Field     -> element.declaringClass
        is Method    -> element.declaringClass
        is Parameter -> element.declaringExecutable.declaringClass
        else         -> null
    }

    private fun scopeMark(element: AnnotatedElement): Boolean? {
        val names = element.annotations.map { it.annotationClass.java.name }
        return when {
            NULL_MARKED_FQN in names   -> true
            NULL_UNMARKED_FQN in names -> false
            else                       -> null
        }
    }
}
