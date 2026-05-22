package com.acme.api

import com.acme.api.dto.ItemDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Annotated `@RestController` co-located with DSL configs. Verifies:
 *  - DSL and annotated controllers coexist in the same project / IT run.
 *  - A type referenced by BOTH a DSL config (ItemRouterConfig → ItemDto) AND
 *    this controller graduates to `types.ws` via the shared-ownership rule.
 */
@RestController
@RequestMapping("/health")
class HealthController {

    @GetMapping
    fun ping(): String = "ok"

    @GetMapping("/latest")
    fun latest(): ItemDto = throw NotImplementedError()
}
