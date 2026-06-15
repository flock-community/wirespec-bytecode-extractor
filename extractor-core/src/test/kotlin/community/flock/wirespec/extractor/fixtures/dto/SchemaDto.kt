package community.flock.wirespec.extractor.fixtures.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.Optional

data class SchemaDto(
    @field:Schema(description = "The user's display name", required = true)
    @field:NotNull
    val name: String,

    val maybe: Optional<String>,

    val nullable: String?,
    val notNullablePrimitive: Int,
)

/** Fixture for testing JSR-305 / IDE-style nullability annotations (step 4). */
object JsrNullableFixtures {
    /** Parameter annotated with @Nullable — kotlinNullable skips non-Field elements, so step 4 fires. */
    @Suppress("unused")
    fun withNullable(@org.jetbrains.annotations.Nullable maybe: String): Unit = Unit
}
