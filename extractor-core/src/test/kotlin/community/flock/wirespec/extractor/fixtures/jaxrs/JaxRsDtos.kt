package community.flock.wirespec.extractor.fixtures.jaxrs

data class CreateUserDto(
    val name: String,
    val age: Int,
)

data class ErrorDto(
    val code: String,
    val message: String,
)
