package community.flock.wirespec.bytecode.extractor.fixtures.dsl

import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router

/**
 * Fixture: WebFlux Kotlin DSL with nesting. Exercises both forms of `nest` —
 * `String.nest { }` and predicate-less `GET/POST` calls inside.
 */
class WebFluxRouterConfig {

    fun routes(h: WebFluxRouterHandler): RouterFunction<ServerResponse> = router {
        "/users".nest {
            GET("", h::list)
            POST("", h::create)
            "/{id}".nest {
                GET("", h::getOne)
                DELETE("", h::delete)
            }
        }
    }
}
