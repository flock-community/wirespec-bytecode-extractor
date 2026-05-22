package com.acme.api

import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router

/**
 * Top-level WebFlux Kotlin DSL fixture. Exercises:
 *  - `router { }` entry point
 *  - String.nest with a path prefix
 *  - All HTTP methods on the DSL (GET / POST / PUT / DELETE)
 *  - Method references with bodyToMono / bodyToFlux body extraction
 *  - `@ApiResponses`-annotated handler producing multiple Wirespec responses
 *  - Nesting two levels deep ({id})
 */
class ItemRouterConfig {

    fun routes(h: ItemHandler): RouterFunction<ServerResponse> = router {
        "/items".nest {
            GET("", h::list)
            POST("", h::create)
            POST("/bulk", h::bulkUpsert)
            GET("/describe", h::describe)
            "/{id}".nest {
                GET("", h::getOne)
                PUT("", h::update)
                DELETE("", h::delete)
            }
        }
    }
}
