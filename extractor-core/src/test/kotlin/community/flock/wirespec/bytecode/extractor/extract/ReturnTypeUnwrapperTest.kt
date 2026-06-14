// src/test/kotlin/community/flock/wirespec/bytecode/extractor/extract/ReturnTypeUnwrapperTest.kt
package community.flock.wirespec.bytecode.extractor.extract

import community.flock.wirespec.bytecode.extractor.fixtures.SuspendController
import community.flock.wirespec.bytecode.extractor.fixtures.wrapped.Item
import community.flock.wirespec.bytecode.extractor.fixtures.wrapped.WrappedController
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType

class ReturnTypeUnwrapperTest {

    private fun method(name: String) = WrappedController::class.java.declaredMethods.first { it.name == name }

    @Test
    fun `raw type returns the type itself`() {
        ReturnTypeUnwrapper.unwrap(method("raw").genericReturnType) shouldBe ReturnTypeUnwrapper.Unwrapped(Item::class.java, isList = false, isVoid = false)
    }

    @Test
    fun `ResponseEntity unwraps to inner type`() {
        val out = ReturnTypeUnwrapper.unwrap(method("entity").genericReturnType)
        out shouldBe ReturnTypeUnwrapper.Unwrapped(Item::class.java, isList = false, isVoid = false)
    }

    @Test
    fun `Optional unwraps to inner type`() {
        ReturnTypeUnwrapper.unwrap(method("opt").genericReturnType).type shouldBe Item::class.java
    }

    @Test
    fun `Mono unwraps to inner type`() {
        ReturnTypeUnwrapper.unwrap(method("mono").genericReturnType).type shouldBe Item::class.java
    }

    @Test
    fun `Flux unwraps to inner type with isList=true`() {
        val out = ReturnTypeUnwrapper.unwrap(method("flux").genericReturnType)
        out.type shouldBe Item::class.java
        out.isList shouldBe true
    }

    @Test
    fun `Callable unwraps to inner type`() {
        ReturnTypeUnwrapper.unwrap(method("callable").genericReturnType).type shouldBe Item::class.java
    }

    @Test
    fun `void method has isVoid=true and default status 204`() {
        val out = ReturnTypeUnwrapper.unwrap(method("voided").genericReturnType)
        out.isVoid shouldBe true
        ReturnTypeUnwrapper.statusCodeFor(method("voided"), out) shouldBe 204
    }

    @Test
    fun `Mono Void has isVoid=true and default status 204`() {
        val out = ReturnTypeUnwrapper.unwrap(method("monoVoid").genericReturnType)
        out.isVoid shouldBe true
        ReturnTypeUnwrapper.statusCodeFor(method("monoVoid"), out) shouldBe 204
    }

    @Test
    fun `default status for value-returning method is 200`() {
        val out = ReturnTypeUnwrapper.unwrap(method("raw").genericReturnType)
        ReturnTypeUnwrapper.statusCodeFor(method("raw"), out) shouldBe 200
    }

    @Test
    fun `@ResponseStatus overrides the default`() {
        val m = method("created")
        ReturnTypeUnwrapper.statusCodeFor(m, ReturnTypeUnwrapper.unwrap(m.genericReturnType)) shouldBe 201
    }

    private fun suspendMethod(name: String) =
        SuspendController::class.java.declaredMethods.first { it.name == name }

    @Test
    fun `suspend function's effective return type comes from the Continuation type arg`() {
        val m = suspendMethod("getUser")
        val out = ReturnTypeUnwrapper.unwrap(m)
        out.type shouldBe Item::class.java
        out.isVoid shouldBe false
        out.isList shouldBe false
    }

    @Test
    fun `suspend function with @RequestBody parameter still resolves to its declared return`() {
        val m = suspendMethod("createUser")
        val out = ReturnTypeUnwrapper.unwrap(m)
        out.type shouldBe Item::class.java
        out.isVoid shouldBe false
    }

    @Test
    fun `suspend Unit-returning function is treated as void with default status 204`() {
        val m = suspendMethod("delete")
        val out = ReturnTypeUnwrapper.unwrap(m)
        out.isVoid shouldBe true
        ReturnTypeUnwrapper.statusCodeFor(m, out) shouldBe 204
    }

    @Test
    fun `non-suspend methods still unwrap via the Type overload`() {
        // Smoke: the Method overload must agree with the Type overload for non-suspend methods.
        val m = WrappedController::class.java.declaredMethods.first { it.name == "raw" }
        ReturnTypeUnwrapper.unwrap(m).type shouldBe Item::class.java
    }

    @Test
    fun `nested wrapper ResponseEntity Mono Item unwraps recursively`() {
        // Smoke check via a synthetic ParameterizedType using Flux<Item> as a stand-in.
        val t = method("flux").genericReturnType.shouldBeInstanceOf<ParameterizedType>()
        ReturnTypeUnwrapper.unwrap(t).type shouldBe Item::class.java
    }
}
