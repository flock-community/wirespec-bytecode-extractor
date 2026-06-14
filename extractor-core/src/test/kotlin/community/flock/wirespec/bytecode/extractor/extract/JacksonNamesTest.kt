package community.flock.wirespec.bytecode.extractor.extract

import community.flock.wirespec.bytecode.extractor.fixtures.dto.JacksonDto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JacksonNamesTest {

    @Test
    fun `JsonProperty rename is honored`() {
        val f = JacksonDto::class.java.getDeclaredField("userId")
        JacksonNames.effectiveName(f, original = "userId") shouldBe "user_id"
    }

    @Test
    fun `JsonIgnore makes the field ignored`() {
        val f = JacksonDto::class.java.getDeclaredField("internalNote")
        JacksonNames.isIgnored(f) shouldBe true
    }

    @Test
    fun `Unannotated field uses original name`() {
        val f = JacksonDto::class.java.getDeclaredField("visible")
        JacksonNames.effectiveName(f, original = "visible") shouldBe "visible"
        JacksonNames.isIgnored(f) shouldBe false
    }
}
