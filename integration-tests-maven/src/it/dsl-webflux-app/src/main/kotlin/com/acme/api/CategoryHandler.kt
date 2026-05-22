package com.acme.api

import com.acme.api.dto.CategoryDto
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class CategoryHandler {
    fun list(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
    fun create(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono(CategoryDto::class.java).then(ServerResponse.ok().build())
}
