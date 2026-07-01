package community.flock.wirespec.extractor.fixtures.jaxrs

import community.flock.wirespec.extractor.fixtures.dto.UserDto
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

/**
 * A JAX-RS resource declared as a Kotlin `fun interface` — the shape reported in
 * issue #19. Annotations live on the interface (the concrete implementation is
 * registered elsewhere), so the scanner must accept interfaces, not just
 * concrete classes.
 */
@Path("/api")
fun interface SomeApi {
    @GET
    fun getSome(): UserDto
}
