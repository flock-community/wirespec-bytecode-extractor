package community.flock.wirespec.spring.extractor.fixtures.dsl;

import community.flock.wirespec.spring.extractor.fixtures.dto.UserDto;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Fixture: Java fluent `RouterFunctions.route()` builder. Verifies that the
 * walker handles method references compiled to {@code INVOKEDYNAMIC} for SAM
 * conversion to {@code HandlerFunction}.
 */
public class JavaWebFluxRouterConfig {

    public RouterFunction<ServerResponse> routes(JavaHandler handler) {
        return RouterFunctions.route()
                .GET("/things", handler::list)
                .POST("/things", handler::create)
                .GET("/things/{id}", handler::getOne)
                .build();
    }

    public static class JavaHandler {
        public Mono<ServerResponse> list(ServerRequest request) {
            return ServerResponse.ok().build();
        }
        public Mono<ServerResponse> create(ServerRequest request) {
            request.bodyToMono(UserDto.class);
            return ServerResponse.ok().build();
        }
        public Mono<ServerResponse> getOne(ServerRequest request) {
            return ServerResponse.ok().build();
        }
    }
}
