// src/test/kotlin/community/flock/wirespec/bytecode/extractor/fixtures/MultiMappingController.kt
package community.flock.wirespec.bytecode.extractor.fixtures

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/multi")
class MultiMappingController {
    @RequestMapping(method = [RequestMethod.GET, RequestMethod.HEAD])
    fun both(): String = "x"
}
