package community.flock.wirespec.spring.extractor.fixtures.dsl

import community.flock.wirespec.spring.extractor.fixtures.dto.UserDto
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/** WebFlux non-suspend handler. Used by [WebFluxRouterConfig]. */
class WebFluxRouterHandler {
    fun list(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
    fun getOne(request: ServerRequest): Mono<ServerResponse> = ServerResponse.ok().build()
    fun create(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono(UserDto::class.java).then(ServerResponse.ok().build())
    fun delete(request: ServerRequest): Mono<ServerResponse> = ServerResponse.noContent().build()
}
