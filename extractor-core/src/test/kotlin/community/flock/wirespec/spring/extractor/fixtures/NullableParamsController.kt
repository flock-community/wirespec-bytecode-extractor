package community.flock.wirespec.spring.extractor.fixtures

import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Optional

/**
 * Exercises nullability across every Spring binding source. A binding parameter
 * is non-null by default (Spring resolves it as required) and becomes nullable
 * when declared `required = false`, given a `defaultValue`, typed as a Kotlin
 * nullable / `Optional<T>`, or annotated `@Nullable`.
 */
@RestController
class NullableParamsController {

    @GetMapping("/n/{id}")
    fun get(
        @PathVariable id: String,
        @RequestParam q: String,
        @RequestParam(required = false) optional: String?,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestHeader(value = "X-Trace", required = false) trace: String?,
        @CookieValue(value = "session", required = false) session: String?,
        @RequestParam maybe: Optional<String>,
    ): String = ""

    @PostMapping("/n")
    fun post(@RequestBody(required = false) body: String?): String = ""

    @PostMapping("/n-required")
    fun postRequired(@RequestBody body: String): String = ""
}
