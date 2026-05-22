package com.acme.api;

import com.acme.api.dto.ThingDto;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public class ThingHandler {
    public Mono<ServerResponse> list(ServerRequest request) {
        return ServerResponse.ok().build();
    }
    public Mono<ServerResponse> getOne(ServerRequest request) {
        return ServerResponse.ok().build();
    }
    public Mono<ServerResponse> create(ServerRequest request) {
        request.bodyToMono(ThingDto.class);
        return ServerResponse.ok().build();
    }
    public Mono<ServerResponse> bulkCreate(ServerRequest request) {
        request.bodyToFlux(ThingDto.class);
        return ServerResponse.ok().build();
    }
    public Mono<ServerResponse> delete(ServerRequest request) {
        return ServerResponse.noContent().build();
    }
}
