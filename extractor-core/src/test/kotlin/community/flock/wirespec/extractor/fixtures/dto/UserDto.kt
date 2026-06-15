package community.flock.wirespec.extractor.fixtures.dto

data class UserDto(
    val id: String,
    val age: Int,
    val active: Boolean,
    val role: Role,
    val tags: List<String>,
)
