package com.acme.api

import com.acme.api.dto.ItemDto
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class ItemHandler {

    fun list(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()

    fun getOne(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()

    /** Body extraction via `bodyToMono(Class)` — the extractor reads this as the request body. */
    fun create(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono(ItemDto::class.java).then(ServerResponse.ok().build())

    /** `bodyToFlux(Class)` is reported as a list body. */
    fun bulkUpsert(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToFlux(ItemDto::class.java).then(ServerResponse.ok().build())

    fun update(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono(ItemDto::class.java).then(ServerResponse.ok().build())

    fun delete(request: ServerRequest): Mono<ServerResponse> = ServerResponse.noContent().build()

    /**
     * `@ApiResponses` on a DSL handler is honored by the extractor — it produces
     * one Wirespec response per declared status, with the schema implementation
     * as the body type.
     */
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = ItemDto::class))]),
        ApiResponse(responseCode = "404"),
    )
    fun describe(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
}
