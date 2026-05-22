package community.flock.wirespec.spring.extractor.extract.dsl

import community.flock.wirespec.spring.extractor.extract.TypeExtractor
import community.flock.wirespec.spring.extractor.fixtures.dsl.JavaWebFluxRouterConfig
import community.flock.wirespec.spring.extractor.fixtures.dsl.MvcRouterConfig
import community.flock.wirespec.spring.extractor.fixtures.dsl.WebFluxRouterConfig
import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.spring.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.spring.extractor.model.WireType
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class DslEndpointExtractorTest {

    private fun extractor() = DslEndpointExtractor(
        types = TypeExtractor(),
        classLoader = javaClass.classLoader,
    )

    @Test
    fun `produces one endpoint per DSL route with controller name from the config class`() {
        val routes = DslBytecodeWalker.walk(WebFluxRouterConfig::class.java)
        val endpoints = extractor().extract(WebFluxRouterConfig::class.java, routes)

        endpoints.map { it.controllerSimpleName }.toSet() shouldBe setOf("WebFluxRouterConfig")
        endpoints.map { it.method to it.pathSegments.joinToString("/") {
            when (it) { is PathSegment.Literal -> it.value; is PathSegment.Variable -> "{${it.name}}" }
        } } shouldContainExactlyInAnyOrder listOf(
            HttpMethod.GET    to "users",
            HttpMethod.POST   to "users",
            HttpMethod.GET    to "users/{id}",
            HttpMethod.DELETE to "users/{id}",
        )
    }

    @Test
    fun `infers request body from Kotlin handler bodyToMono`() {
        val routes = DslBytecodeWalker.walk(WebFluxRouterConfig::class.java)
        val endpoints = extractor().extract(WebFluxRouterConfig::class.java, routes)
        val create = endpoints.single { it.method == HttpMethod.POST && it.name == "Create" }
        create.requestBody.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `infers request body for Spring MVC body(Class) handler`() {
        val routes = DslBytecodeWalker.walk(MvcRouterConfig::class.java)
        val endpoints = extractor().extract(MvcRouterConfig::class.java, routes)
        val create = endpoints.single { it.method == HttpMethod.POST }
        create.requestBody.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `infers request body for Java handler bodyToMono`() {
        val routes = DslBytecodeWalker.walk(JavaWebFluxRouterConfig::class.java)
        val endpoints = extractor().extract(JavaWebFluxRouterConfig::class.java, routes)
        val create = endpoints.single { it.method == HttpMethod.POST }
        create.requestBody.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `emits one 200 response with empty body when handler returns ServerResponse`() {
        val routes = DslBytecodeWalker.walk(WebFluxRouterConfig::class.java)
        val endpoints = extractor().extract(WebFluxRouterConfig::class.java, routes)
        val getOne = endpoints.single { it.method == HttpMethod.GET && it.pathSegments.size == 2 }
        getOne.responses.size shouldBe 1
        getOne.responses.single().statusCode shouldBe 200
        getOne.responses.single().body shouldBe null
    }
}
