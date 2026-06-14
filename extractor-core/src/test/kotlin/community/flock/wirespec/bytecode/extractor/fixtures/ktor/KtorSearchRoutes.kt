package community.flock.wirespec.bytecode.extractor.fixtures.ktor

import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Fixture: query and header parameters read imperatively inside the handler.
 * `active`/`page` carry a type conversion (`toBoolean`/`toInt`); `q`, `X-Trace`,
 * and `Authorization` stay `String?`.
 */
fun Application.searchRoutes() {
    routing {
        get("/search") {
            val active = call.request.queryParameters["active"]?.toBoolean()
            val page = call.request.queryParameters["page"]?.toInt()
            val q = call.request.queryParameters["q"]
            val trace = call.request.headers["X-Trace"]
            val auth = call.request.header("Authorization")
            call.respond("$active$page$q$trace$auth")
        }
    }
}
