package community.flock.wirespec.bytecode.extractor.fixtures.dsl

import community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * Fixture: WebFlux `coRouter` (suspend handlers).
 */
class WebFluxCoRouterConfig {

    fun routes(h: CoHandler): RouterFunction<ServerResponse> = coRouter {
        "/items".nest {
            GET("", h::list)
            POST("", h::create)
            PUT("/{id}", h::update)
        }
    }

    class CoHandler {
        // Bodies are unused — the walker only inspects the router-config bytecode and
        // the handler bytecode for `bodyToMono(Class)` patterns. `block()` keeps the
        // fixture independent of kotlinx-coroutines-reactor.
        suspend fun list(req: ServerRequest): ServerResponse =
            ServerResponse.ok().build().block()!!
        suspend fun create(req: ServerRequest): ServerResponse {
            req.bodyToMono(UserDto::class.java)
            return ServerResponse.ok().build().block()!!
        }
        suspend fun update(req: ServerRequest): ServerResponse =
            ServerResponse.ok().build().block()!!
    }
}
