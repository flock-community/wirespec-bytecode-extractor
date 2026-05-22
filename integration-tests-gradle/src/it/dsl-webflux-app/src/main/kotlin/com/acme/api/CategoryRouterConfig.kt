package com.acme.api

import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router

/**
 * Second router config in the same fixture — verifies that the extractor
 * produces one `<Config>.ws` file per RouterFunction-producing class, and that
 * the shared DTO (CategoryDto) graduates to `types.ws` only when referenced by
 * two configs. Since only this config references CategoryDto, it stays
 * controller-local (inlined into CategoryRouterConfig.ws).
 */
class CategoryRouterConfig {

    fun routes(h: CategoryHandler): RouterFunction<ServerResponse> = router {
        "/categories".nest {
            GET("", h::list)
            POST("", h::create)
        }
    }
}
