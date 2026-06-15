// src/test/kotlin/community/flock/wirespec/extractor/fixtures/SuspendController.kt
package community.flock.wirespec.extractor.fixtures

import community.flock.wirespec.extractor.fixtures.wrapped.Item
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Fixture for Kotlin `suspend` endpoints. A suspend function compiles to a
 * Java method with an extra trailing `Continuation<? super T>` parameter and
 * an erased `Object` return type. The extractor must NOT walk that synthetic
 * parameter as a body (which would leak `Continuation` / `CoroutineContext`
 * into the schema) and MUST recover the real response type from the
 * Continuation's type argument.
 */
@RestController
class SuspendController {

    @GetMapping("/u/{id}")
    suspend fun getUser(@PathVariable id: String): Item = Item(id)

    @PostMapping("/u")
    suspend fun createUser(@RequestBody body: Item): Item = body

    @DeleteMapping("/u/{id}")
    suspend fun delete(@PathVariable id: String) { /* Unit-returning suspend */ }
}
