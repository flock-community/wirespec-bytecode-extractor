package community.flock.wirespec.extractor.fixtures.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

data class JacksonDto(
    @field:JsonProperty("user_id") val userId: String,
    @field:JsonIgnore val internalNote: String,
    val visible: String,
)
