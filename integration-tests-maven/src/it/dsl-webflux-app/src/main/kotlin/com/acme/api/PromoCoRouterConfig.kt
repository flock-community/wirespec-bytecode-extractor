package com.acme.api

import com.acme.api.dto.ItemDto
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter
import reactor.core.publisher.Mono

/**
 * Suspend / `coRouter` DSL fixture. Verifies:
 *  - `coRouter { }` entry point is recognised
 *  - Suspend handlers (`suspend fun (ServerRequest) -> ServerResponse`) have
 *    their method references resolved correctly
 *  - Request body inference still works through suspend handler bytecode
 *
 * Handler bodies use `Mono.block()` instead of `kotlinx-coroutines-reactor`'s
 * `buildAndAwait()` so the fixture has no coroutines-extras dependency. The
 * extractor never executes these.
 */
class PromoCoRouterConfig {

    fun routes(h: PromoCoHandler): RouterFunction<ServerResponse> = coRouter {
        "/promos".nest {
            GET("", h::list)
            POST("", h::create)
        }
    }

    class PromoCoHandler {
        suspend fun list(req: ServerRequest): ServerResponse =
            ServerResponse.ok().build().awaitOk()
        suspend fun create(req: ServerRequest): ServerResponse {
            req.bodyToMono(ItemDto::class.java)
            return ServerResponse.ok().build().awaitOk()
        }

        // Local extension to keep the bodies compile-safe without pulling in
        // kotlinx-coroutines-reactor; never actually called at extract time.
        private fun Mono<ServerResponse>.awaitOk(): ServerResponse = block()!!
    }
}
