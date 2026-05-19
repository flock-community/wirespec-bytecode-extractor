// src/test/kotlin/community/flock/wirespec/spring/extractor/extract/ApiResponseExtractorTest.kt
package community.flock.wirespec.spring.extractor.extract

import community.flock.wirespec.spring.extractor.fixtures.multiresponse.MultiResponseController
import community.flock.wirespec.spring.extractor.fixtures.wrapped.WrappedController
import community.flock.wirespec.spring.extractor.model.WireType
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ApiResponseExtractorTest {

    private fun extractAll(target: Class<*>) = EndpointExtractor(TypeExtractor()).extract(target)

    @Test
    fun `multi-status endpoint emits one response per ApiResponse`() {
        val ep = extractAll(MultiResponseController::class.java).single { it.name == "GetUser" }
        ep.responses.map { it.statusCode } shouldContainExactlyInAnyOrder listOf(200, 404)
    }

    @Test
    fun `success ApiResponse without content falls back to method return type`() {
        val ep = extractAll(MultiResponseController::class.java).single { it.name == "GetUser" }
        val ok = ep.responses.single { it.statusCode == 200 }
        ok.body.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `non-success ApiResponse uses content schema implementation`() {
        val ep = extractAll(MultiResponseController::class.java).single { it.name == "GetUser" }
        val err = ep.responses.single { it.statusCode == 404 }
        err.body.shouldBeInstanceOf<WireType.Ref>().name shouldBe "ErrorDto"
    }

    @Test
    fun `array schema produces a ListOf body`() {
        val ep = extractAll(MultiResponseController::class.java).single { it.name == "ListUsers" }
        val ok = ep.responses.single { it.statusCode == 200 }
        val list = ok.body.shouldBeInstanceOf<WireType.ListOf>()
        list.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `error response with explicit schema overrides any list-form return`() {
        val ep = extractAll(MultiResponseController::class.java).single { it.name == "ListUsers" }
        val err = ep.responses.single { it.statusCode == 500 }
        err.body.shouldBeInstanceOf<WireType.Ref>().name shouldBe "ErrorDto"
    }

    @Test
    fun `non-numeric response codes are dropped`() {
        val warnings = mutableListOf<String>()
        val ep = EndpointExtractor(TypeExtractor(), onWarn = { warnings += it })
            .extract(MultiResponseController::class.java)
            .single { it.name == "GetNote" }
        ep.responses.map { it.statusCode } shouldBe listOf(200)
        warnings.any { "default" in it } shouldBe true
    }

    @Test
    fun `endpoints without ApiResponse annotations keep single-response behavior`() {
        // WrappedController fixtures have no springdoc annotations.
        val endpoints = extractAll(WrappedController::class.java)
        endpoints.forAll { it.responses.size shouldBe 1 }
        val raw = endpoints.single { it.name == "Raw" }
        raw.responses.single().statusCode shouldBe 200
        val voided = endpoints.single { it.name == "Voided" }
        voided.responses.single().statusCode shouldBe 204
        voided.responses.single().body shouldBe null
        val created = endpoints.single { it.name == "Created" }
        created.responses.single().statusCode shouldBe 201
    }
}

private fun <T> Iterable<T>.forAll(block: (T) -> Unit) = forEach(block)
