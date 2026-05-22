package community.flock.wirespec.spring.extractor.extract.dsl

import community.flock.wirespec.spring.extractor.fixtures.dsl.JavaWebFluxRouterConfig
import community.flock.wirespec.spring.extractor.fixtures.dsl.MvcRouterConfig
import community.flock.wirespec.spring.extractor.fixtures.dsl.WebFluxCoRouterConfig
import community.flock.wirespec.spring.extractor.fixtures.dsl.WebFluxRouterConfig
import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DslBytecodeWalkerTest {

    @Test
    fun `walks WebFlux Kotlin router with nested paths`() {
        val routes = DslBytecodeWalker.walk(WebFluxRouterConfig::class.java)
        val pathsByMethod = routes.map { it.method to it.path }
        pathsByMethod shouldContainExactlyInAnyOrder listOf(
            HttpMethod.GET    to "/users",
            HttpMethod.POST   to "/users",
            HttpMethod.GET    to "/users/{id}",
            HttpMethod.DELETE to "/users/{id}",
        )
    }

    @Test
    fun `resolves Kotlin DSL handler method references`() {
        val routes = DslBytecodeWalker.walk(WebFluxRouterConfig::class.java)
        val handlers = routes.map { it.handlerName }
        handlers shouldContainAll listOf("list", "create", "getOne", "delete")
        // Owner is the same handler class.
        routes.forEach {
            it.handlerOwnerInternal shouldBe
                "community/flock/wirespec/spring/extractor/fixtures/dsl/WebFluxRouterHandler"
        }
    }

    @Test
    fun `walks WebFlux coRouter suspend handlers`() {
        val routes = DslBytecodeWalker.walk(WebFluxCoRouterConfig::class.java)
        val pathsByMethod = routes.map { it.method to it.path }
        pathsByMethod shouldContainExactlyInAnyOrder listOf(
            HttpMethod.GET  to "/items",
            HttpMethod.POST to "/items",
            HttpMethod.PUT  to "/items/{id}",
        )
    }

    @Test
    fun `walks Spring MVC Kotlin router`() {
        val routes = DslBytecodeWalker.walk(MvcRouterConfig::class.java)
        val pathsByMethod = routes.map { it.method to it.path }
        pathsByMethod shouldContainExactlyInAnyOrder listOf(
            HttpMethod.GET  to "/api/orders",
            HttpMethod.POST to "/api/orders",
        )
    }

    @Test
    fun `walks Java fluent RouterFunctions builder`() {
        val routes = DslBytecodeWalker.walk(JavaWebFluxRouterConfig::class.java)
        val pathsByMethod = routes.map { it.method to it.path }
        pathsByMethod shouldContainExactlyInAnyOrder listOf(
            HttpMethod.GET  to "/things",
            HttpMethod.POST to "/things",
            HttpMethod.GET  to "/things/{id}",
        )
    }
}
