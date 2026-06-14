package community.flock.wirespec.bytecode.extractor.extract

import community.flock.wirespec.bytecode.extractor.fixtures.dto.JsrNullableFixtures
import community.flock.wirespec.bytecode.extractor.fixtures.dto.SchemaDto
import community.flock.wirespec.bytecode.extractor.fixtures.dto.jspecify.JSpecifyParams
import community.flock.wirespec.bytecode.extractor.fixtures.dto.jspecify.NullMarkedDto
import community.flock.wirespec.bytecode.extractor.fixtures.dto.jspecify.PlainNullnessDto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NullabilityResolverTest {

    @Test
    fun `Java primitive int is non-null`() {
        val f = SchemaDto::class.java.getDeclaredField("notNullablePrimitive")
        NullabilityResolver.isNullable(f, declaredJavaType = Int::class.javaPrimitiveType!!) shouldBe false
    }

    @Test
    fun `Optional field is nullable`() {
        val f = SchemaDto::class.java.getDeclaredField("maybe")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `Kotlin nullable property is nullable`() {
        val f = SchemaDto::class.java.getDeclaredField("nullable")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `@NotNull or @Schema(required=true) flips to non-null`() {
        val f = SchemaDto::class.java.getDeclaredField("name")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe false
    }

    @Test
    fun `Schema description is exposed`() {
        val f = SchemaDto::class.java.getDeclaredField("name")
        NullabilityResolver.schemaDescription(f) shouldBe "The user's display name"
    }

    @Test
    fun `JSR-305 @Nullable on a parameter flips to nullable`() {
        // Parameters are not Fields, so kotlinNullable() short-circuits and the
        // annotation step fires.
        val p = JsrNullableFixtures::class.java.getDeclaredMethod("withNullable", String::class.java).parameters[0]
        NullabilityResolver.isNullable(p, declaredJavaType = p.type) shouldBe true
    }

    // --- JSpecify ---------------------------------------------------------

    @Test
    fun `JSpecify @NullMarked makes unannotated fields non-null`() {
        val f = NullMarkedDto::class.java.getDeclaredField("required")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe false
    }

    @Test
    fun `JSpecify @Nullable inside @NullMarked stays nullable`() {
        val f = NullMarkedDto::class.java.getDeclaredField("optional")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `JSpecify @Nullable on a boxed type inside @NullMarked is nullable`() {
        val f = NullMarkedDto::class.java.getDeclaredField("optionalBoxed")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `primitive stays non-null inside @NullMarked`() {
        val f = NullMarkedDto::class.java.getDeclaredField("primitive")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe false
    }

    @Test
    fun `JSpecify @Nullable field without @NullMarked is nullable`() {
        val f = PlainNullnessDto::class.java.getDeclaredField("maybe")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `JSpecify @NonNull field without @NullMarked is non-null`() {
        val f = PlainNullnessDto::class.java.getDeclaredField("always")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe false
    }

    @Test
    fun `unannotated field without @NullMarked defaults to nullable`() {
        val f = PlainNullnessDto::class.java.getDeclaredField("unspecified")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    // --- Parameter nullability -------------------------------------------

    @Test
    fun `required binding parameter is non-null by default`() {
        val p = JSpecifyParams::class.java.getDeclaredMethod("unannotatedParam", String::class.java).parameters[0]
        NullabilityResolver.isParameterNullable(p, springOptional = false) shouldBe false
    }

    @Test
    fun `optional binding parameter is nullable`() {
        val p = JSpecifyParams::class.java.getDeclaredMethod("unannotatedParam", String::class.java).parameters[0]
        NullabilityResolver.isParameterNullable(p, springOptional = true) shouldBe true
    }

    @Test
    fun `JSpecify @Nullable parameter is nullable even when required`() {
        val p = JSpecifyParams::class.java.getDeclaredMethod("nullableParam", String::class.java).parameters[0]
        NullabilityResolver.isParameterNullable(p, springOptional = false) shouldBe true
    }

    @Test
    fun `JSpecify @NonNull parameter is non-null even when optional`() {
        val p = JSpecifyParams::class.java.getDeclaredMethod("nonNullParam", String::class.java).parameters[0]
        NullabilityResolver.isParameterNullable(p, springOptional = true) shouldBe false
    }
}
