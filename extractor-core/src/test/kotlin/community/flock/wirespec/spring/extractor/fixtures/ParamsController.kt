// src/test/kotlin/community/flock/wirespec/spring/extractor/fixtures/ParamsController.kt
package community.flock.wirespec.spring.extractor.fixtures

import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import community.flock.wirespec.spring.extractor.fixtures.dto.Role

@RestController
class ParamsController {

    @GetMapping("/users/{role}")
    fun getByRole(@PathVariable role: Role): String = ""

    @GetMapping("/items/{id}")
    fun getItem(
        @PathVariable id: String,
        @RequestParam q: String,
        @RequestParam(required = false) page: Int?,
        @RequestHeader("X-Trace") trace: String,
        @CookieValue("session") session: String,
    ): String = ""

    @PostMapping("/items")
    fun postItem(@RequestBody body: String): String = ""

    @GetMapping("/named")
    fun named(
        @RequestParam(name = "explicitName") explicit: String,
    ): String = ""
}
