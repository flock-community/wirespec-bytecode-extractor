package community.flock.wirespec.extractor.fixtures

import org.springframework.stereotype.Controller

@Controller
class PlainController {
    // No @ResponseBody anywhere — should be skipped.
    fun renderView() = "view"
}
