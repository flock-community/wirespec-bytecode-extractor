package community.flock.wirespec.extractor.fixtures.ktor

import community.flock.wirespec.extractor.fixtures.dto.Role
import community.flock.wirespec.extractor.fixtures.dto.UserDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Fixture: Ktor server routing tree with nesting (`route`), an inline-path verb
 * (`get("/health")`), a `receive`d request body, list/object responses, and an
 * explicit status code (`Created`, `NoContent`).
 */
fun Application.userRoutes() {
    routing {
        get("/health") {
            call.respond("ok")
        }
        route("/users") {
            get {
                call.respond(listOf<UserDto>())
            }
            post {
                val dto = call.receive<UserDto>()
                call.respond(HttpStatusCode.Created, dto)
            }
            route("/{id}") {
                get {
                    call.respond(UserDto("1", 2, true, Role.ADMIN, emptyList()))
                }
                delete {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
