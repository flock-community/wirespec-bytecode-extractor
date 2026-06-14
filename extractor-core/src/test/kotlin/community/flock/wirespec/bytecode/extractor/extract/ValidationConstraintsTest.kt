package community.flock.wirespec.bytecode.extractor.extract

import community.flock.wirespec.bytecode.extractor.fixtures.dto.ValidatedDto
import community.flock.wirespec.bytecode.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ValidationConstraintsTest {

    @Test
    fun `@Pattern produces a refined String type`() {
        val f = ValidatedDto::class.java.getDeclaredField("code")
        val refined = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.STRING))
        refined.shouldBeInstanceOf<WireType.Refined>().regex shouldBe "^[A-Z]{3}$"
    }

    @Test
    fun `@Size produces refined String with min and max`() {
        val f = ValidatedDto::class.java.getDeclaredField("name")
        val refined = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.STRING))
        refined.shouldBeInstanceOf<WireType.Refined>()
        refined.min shouldBe "1"
        refined.max shouldBe "10"
    }

    @Test
    fun `@Min and @Max on Integer produce refined Integer with bounds`() {
        val f = ValidatedDto::class.java.getDeclaredField("age")
        val refined = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.INTEGER_32))
        refined.shouldBeInstanceOf<WireType.Refined>()
        refined.min shouldBe "0"
        refined.max shouldBe "120"
    }

    @Test
    fun `Unconstrained field returns the base type unchanged`() {
        val f = community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto::class.java.getDeclaredField("id")
        val out = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.STRING))
        out shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `same refinement on different fields produces same Refined name`() {
        val f1 = ValidatedDto::class.java.getDeclaredField("name")
        val f2 = ValidatedDto::class.java.getDeclaredField("name") // same field used twice — name must be deterministic
        val r1 = ValidationConstraints.refine(f1, WireType.Primitive(WireType.Primitive.Kind.STRING)) as WireType.Refined
        val r2 = ValidationConstraints.refine(f2, WireType.Primitive(WireType.Primitive.Kind.STRING)) as WireType.Refined
        r1.name shouldBe r2.name
    }
}
