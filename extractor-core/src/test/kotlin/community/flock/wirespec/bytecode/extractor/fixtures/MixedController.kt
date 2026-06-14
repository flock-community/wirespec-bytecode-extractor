package community.flock.wirespec.bytecode.extractor.fixtures

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class MixedController {
    @GetMapping("/mixed")
    @ResponseBody
    fun api(): String = "data"
}
