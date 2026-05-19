// src/test/kotlin/community/flock/wirespec/spring/extractor/fixtures/multiresponse/MultiResponseController.kt
package community.flock.wirespec.spring.extractor.fixtures.multiresponse

import community.flock.wirespec.spring.extractor.fixtures.dto.UserDto
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

data class ErrorDto(val code: String, val message: String)

@RestController
class MultiResponseController {

    /** Success returns UserDto (from method signature), 404 returns ErrorDto. */
    @GetMapping("/users/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(
            responseCode = "404",
            content = [Content(schema = Schema(implementation = ErrorDto::class))]
        ),
    )
    fun getUser(@PathVariable id: String): UserDto = UserDto(id, 0, true, community.flock.wirespec.spring.extractor.fixtures.dto.Role.MEMBER, emptyList())

    /** Two declared status codes, both with explicit schemas; one is a list. */
    @GetMapping("/users")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            content = [Content(array = ArraySchema(schema = Schema(implementation = UserDto::class)))]
        ),
        ApiResponse(
            responseCode = "500",
            content = [Content(schema = Schema(implementation = ErrorDto::class))]
        ),
    )
    fun listUsers(): List<UserDto> = emptyList()

    /** Non-numeric responseCode ("default") is dropped; success falls back to method return. */
    @GetMapping("/notes/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(
            responseCode = "default",
            content = [Content(schema = Schema(implementation = ErrorDto::class))]
        ),
    )
    fun getNote(@PathVariable id: String): UserDto = UserDto(id, 0, true, community.flock.wirespec.spring.extractor.fixtures.dto.Role.MEMBER, emptyList())
}
