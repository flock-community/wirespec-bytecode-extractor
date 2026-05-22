package com.acme.api

import com.acme.api.dto.OrderDto
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

class OrderHandler {
    fun list(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
    fun getOne(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
    /** Servlet-side body extraction: `ServerRequest.body(Class)`. */
    fun create(request: ServerRequest): ServerResponse {
        request.body(OrderDto::class.java)
        return ServerResponse.ok().build()
    }
    fun cancel(request: ServerRequest): ServerResponse = ServerResponse.noContent().build()
}
