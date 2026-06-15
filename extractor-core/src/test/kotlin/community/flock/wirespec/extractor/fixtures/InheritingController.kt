// src/test/kotlin/community/flock/wirespec/extractor/fixtures/InheritingController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/parent")
abstract class ParentController {
    @GetMapping("/child")
    abstract fun handler(): String
}

@RestController
class InheritingController : ParentController() {
    override fun handler(): String = "ok"
}
