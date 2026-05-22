package com.acme.api

import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * Spring MVC Kotlin DSL (servlet-side equivalent of WebFlux `router`).
 * The DSL bytecode patterns are the same as WebFlux's; the only difference is
 * the package — `org.springframework.web.servlet.function` instead of
 * `org.springframework.web.reactive.function.server`.
 */
class OrderRouterConfig {

    fun routes(h: OrderHandler): RouterFunction<ServerResponse> = router {
        "/orders".nest {
            GET("", h::list)
            POST("", h::create)
            "/{id}".nest {
                GET("", h::getOne)
                DELETE("", h::cancel)
            }
        }
    }
}
