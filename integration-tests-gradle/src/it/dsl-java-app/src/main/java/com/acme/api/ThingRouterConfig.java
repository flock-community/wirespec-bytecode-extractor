package com.acme.api;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Java fluent {@link RouterFunctions#route()} builder. Verifies:
 *  - Top-level builder chain with multiple verbs (GET / POST / PUT / DELETE).
 *  - Method references compiled to {@code INVOKEDYNAMIC LambdaMetafactory}
 *    against {@code HandlerFunction.handle}, resolved by the walker via the
 *    bootstrap method's target handle.
 *  - {@code path(prefix, builder)} nesting carrying through to the routes
 *    defined inside.
 */
public class ThingRouterConfig {

    public RouterFunction<ServerResponse> routes(ThingHandler handler) {
        return RouterFunctions.route()
                .path("/things", builder -> builder
                        .GET("", handler::list)
                        .POST("", handler::create)
                        .POST("/bulk", handler::bulkCreate)
                        .GET("/{id}", handler::getOne)
                        .DELETE("/{id}", handler::delete))
                .build();
    }
}
