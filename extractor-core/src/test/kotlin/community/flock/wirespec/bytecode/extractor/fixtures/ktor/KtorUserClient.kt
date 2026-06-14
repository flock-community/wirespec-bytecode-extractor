package community.flock.wirespec.bytecode.extractor.fixtures.ktor

import community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * Fixture: a Ktor HTTP client wrapper. Exercises a list response (`.body()` of
 * `List<UserDto>`), an object response, and a `setBody` request payload.
 */
class KtorUserClient(private val client: HttpClient) {

    suspend fun listUsers(): List<UserDto> =
        client.get("/users").body()

    suspend fun getUser(): UserDto =
        client.get("/users/single").body()

    suspend fun createUser(dto: UserDto): UserDto =
        client.post("/users") {
            setBody(dto)
        }.body()
}
