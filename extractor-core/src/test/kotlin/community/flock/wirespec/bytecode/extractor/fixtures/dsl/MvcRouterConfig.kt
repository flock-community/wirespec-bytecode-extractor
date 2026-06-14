package community.flock.wirespec.bytecode.extractor.fixtures.dsl

import community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * Fixture: Spring MVC Kotlin DSL (the servlet equivalent of WebFlux's `router`).
 */
class MvcRouterConfig {

    fun routes(h: MvcHandler): RouterFunction<ServerResponse> = router {
        "/api/orders".nest {
            GET("", h::list)
            POST("", h::create)
        }
    }

    class MvcHandler {
        fun list(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
        fun create(request: ServerRequest): ServerResponse {
            request.body(UserDto::class.java)
            return ServerResponse.ok().build()
        }
    }
}
