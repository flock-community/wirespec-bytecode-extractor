package community.flock.wirespec.bytecode.extractor.fixtures.jaxrs

import community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam

/**
 * A JAX-RS root resource that mixes JAX-RS routing/binding annotations with
 * swagger/OpenAPI annotations. Exercises:
 *  - class-level + method-level `@Path`,
 *  - `@GET`/`@POST`/`@DELETE`,
 *  - query/path/header params and an entity body,
 *  - `@Operation(operationId = …)` driving the endpoint name,
 *  - multi-status `@ApiResponses` with a typed error body.
 */
@Path("/users")
class UserResource {

    @GET
    @Operation(operationId = "listUsers")
    fun list(
        @QueryParam("active") active: Boolean?,
        @HeaderParam("X-Trace") trace: String?,
    ): List<UserDto> = emptyList()

    @GET
    @Path("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(
            responseCode = "404",
            content = [Content(schema = Schema(implementation = ErrorDto::class))],
        ),
    )
    fun getOne(@PathParam("id") id: String): UserDto = TODO()

    @POST
    @Operation(operationId = "createUser")
    fun create(body: CreateUserDto): UserDto = TODO()

    @DELETE
    @Path("/{id}")
    fun delete(@PathParam("id") id: String): Unit = Unit
}
