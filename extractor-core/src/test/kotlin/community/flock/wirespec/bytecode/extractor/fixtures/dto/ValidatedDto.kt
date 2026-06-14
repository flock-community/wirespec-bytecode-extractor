package community.flock.wirespec.bytecode.extractor.fixtures.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class ValidatedDto(
    @field:Pattern(regexp = "^[A-Z]{3}$") val code: String,
    @field:Size(min = 1, max = 10) val name: String,
    @field:Min(0) @field:Max(120) val age: Int,
)
