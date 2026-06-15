package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/hello")
class HelloController {
    @GetMapping
    fun hello(): String = "hi"
}
