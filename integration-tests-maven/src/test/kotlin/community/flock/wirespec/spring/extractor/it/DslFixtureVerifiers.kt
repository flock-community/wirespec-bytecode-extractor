package community.flock.wirespec.spring.extractor.it

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifiers for the three Spring functional-DSL fixtures
 * (`dsl-webflux-app`, `dsl-mvc-app`, `dsl-java-app`). Driven from both the
 * Maven and Gradle integration test runners — they accept the resolved
 * `wirespec` output directory (which differs by build tool: `target/wirespec`
 * vs `build/wirespec`) so the verifier itself is build-tool agnostic.
 */
object DslFixtureVerifiers {

    // ------------------------------------------------------------------
    // WebFlux Kotlin DSL: `router { }` + `coRouter { }` + annotated controller
    // ------------------------------------------------------------------
    fun verifyWebFluxApp(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        // Per-config files for each RouterFunction-producing class; an annotated
        // controller (HealthController) lands in its own .ws; ItemDto graduates
        // to types.ws because it's referenced by both ItemRouterConfig and
        // HealthController.
        files.shouldContainExactly(
            "CategoryRouterConfig.ws",
            "HealthController.ws",
            "ItemRouterConfig.ws",
            "PromoCoRouterConfig.ws",
            "types.ws",
        )

        val itemRouter = (wsDir / "ItemRouterConfig.ws").readText()

        // All HTTP methods declared in the DSL are extracted, including those
        // declared two `nest` levels deep. `List`/`Create` also exist in the
        // Category and Promo configs, so global dedup suffixes every occurrence
        // (Category=1, Item=2, Promo=3 — controllers ordered by name).
        itemRouter shouldContain "endpoint List2 GET /items"
        itemRouter shouldContain "endpoint Create2 POST"
        itemRouter shouldContain "endpoint BulkUpsert POST"
        itemRouter shouldContain "/items/bulk"
        itemRouter shouldContain "endpoint GetOne GET /items/{id"
        // `Update` has a body (bodyToMono) so the body token sits between the
        // method and the path — assert the combined shape.
        itemRouter shouldMatch Regex("(?s).*endpoint Update PUT ItemDto /items/\\{id[^}]*}.*")
        itemRouter shouldContain "endpoint Delete DELETE /items/{id"

        // bodyToMono(Class<T>) is inferred as the request body.
        itemRouter shouldMatch Regex("(?s).*endpoint Create2 POST ItemDto /items\\s*->\\s*\\{.*")

        // bodyToFlux(Class<T>) is inferred as a list body.
        itemRouter shouldMatch Regex("(?s).*endpoint BulkUpsert POST ItemDto\\[] /items/bulk.*")

        // Handlers without `bodyToMono` get no request body.
        itemRouter shouldNotContain "endpoint List GET ItemDto"

        // `@ApiResponses` on a DSL handler produces one response per declared status,
        // with the schema-implementation body where present.
        itemRouter shouldMatch Regex("(?s).*endpoint Describe GET /items/describe.*?200 -> ItemDto.*?404.*")

        // DELETE handler returning Mono<ServerResponse> still defaults to 200
        // (DSL handlers can't be statically classified as void), not 204.
        itemRouter shouldMatch Regex("(?s).*endpoint Delete DELETE /items/\\{id[^}]*}\\s*->\\s*\\{\\s*200\\s*->\\s*Unit.*")

        val categoryRouter = (wsDir / "CategoryRouterConfig.ws").readText()
        categoryRouter shouldContain "endpoint List1 GET /categories"
        categoryRouter shouldContain "endpoint Create1 POST CategoryDto /categories"
        // CategoryDto is only referenced by this config — it stays controller-local.
        categoryRouter shouldContain "type CategoryDto"

        // coRouter: suspend handlers are recognised and their `bodyToMono` body
        // extraction works through the suspend signature too.
        val promo = (wsDir / "PromoCoRouterConfig.ws").readText()
        promo shouldContain "endpoint List3 GET /promos"
        promo shouldContain "endpoint Create3 POST ItemDto /promos"

        // Annotated @RestController coexists in the same project / output set.
        val health = (wsDir / "HealthController.ws").readText()
        health shouldContain "endpoint Ping GET /health"
        health shouldContain "endpoint Latest GET /health/latest"
        health shouldMatch Regex("(?s).*endpoint Latest GET /health/latest\\s*->\\s*\\{\\s*200\\s*->\\s*ItemDto.*")

        // Shared types: ItemDto is referenced by ItemRouterConfig, PromoCoRouterConfig,
        // and HealthController → it MUST land in types.ws. CategoryDto stays local.
        val types = (wsDir / "types.ws").readText()
        types shouldContain "type ItemDto"
        types shouldNotContain "type CategoryDto"

        // The shared type must NOT be duplicated into any per-controller file.
        listOf(itemRouter, promo, health).forEach { contents ->
            assertTrue(!Regex("(?m)^\\s*type\\s+ItemDto\\b").containsMatchIn(contents)) {
                "ItemDto leaked into a per-controller .ws file:\n$contents"
            }
        }

        // The DSL extraction must NOT leak Spring framework types as schemas.
        val combined = (itemRouter + promo + health + categoryRouter + types)
        listOf("ServerRequest", "ServerResponse", "Mono", "Flux", "RouterFunction", "HandlerFunction").forEach { fw ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$fw\\b").containsMatchIn(combined)) {
                "Spring framework type $fw leaked as a Wirespec definition:\n$combined"
            }
        }

        // Every top-level definition name must be globally unique across ALL
        // emitted files — not just within one file.
        assertGloballyUniqueNames(wsDir)
    }

    /**
     * Asserts that no top-level Wirespec definition name (endpoint/channel/type/
     * enum/refined) appears in more than one `.ws` file, nor twice in the same
     * file. This is the cross-file uniqueness guarantee the emitter must hold.
     */
    private fun assertGloballyUniqueNames(wsDir: File) {
        val nameRegex = Regex("(?m)^(?:endpoint|channel|type|enum|refined)\\s+(`?[A-Za-z0-9_]+`?)")
        val namesByFile = wsDir.listFiles { f -> f.isFile && f.extension == "ws" }!!
            .associate { f -> f.name to nameRegex.findAll(f.readText()).map { it.groupValues[1].trim('`') }.toList() }
        val duplicates = namesByFile.values.flatten()
            .groupingBy { it }.eachCount()
            .filter { it.value > 1 }
        assertTrue(duplicates.isEmpty()) {
            "Definition names must be globally unique across all .ws files, but found duplicates: " +
                "${duplicates.keys}\nNames per file: $namesByFile"
        }
    }

    // ------------------------------------------------------------------
    // Spring MVC Kotlin DSL (`org.springframework.web.servlet.function.router`)
    // ------------------------------------------------------------------
    fun verifyMvcApp(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        // Single config + no shared types (OrderDto only referenced once → controller-local).
        files.shouldContainExactly("OrderRouterConfig.ws")

        val orderRouter = (wsDir / "OrderRouterConfig.ws").readText()

        orderRouter shouldContain "endpoint List GET /orders"
        orderRouter shouldContain "endpoint Create POST OrderDto /orders"
        orderRouter shouldContain "endpoint GetOne GET /orders/{id"
        orderRouter shouldContain "endpoint Cancel DELETE /orders/{id"

        // OrderDto is controller-local — its definition lives next to the endpoints.
        orderRouter shouldContain "type OrderDto"

        // No accidental leakage of servlet types.
        listOf("ServerRequest", "ServerResponse", "RouterFunction", "HandlerFunction").forEach { fw ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$fw\\b").containsMatchIn(orderRouter)) {
                "Spring servlet-function type $fw leaked as a Wirespec definition:\n$orderRouter"
            }
        }
    }

    // ------------------------------------------------------------------
    // Java fluent builder (`RouterFunctions.route()...build()`)
    // ------------------------------------------------------------------
    fun verifyJavaApp(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly("ThingRouterConfig.ws")

        val thingRouter = (wsDir / "ThingRouterConfig.ws").readText()

        // `path("/things", builder -> ...)` correctly prefixes the inner routes.
        thingRouter shouldContain "endpoint List GET /things"
        thingRouter shouldContain "endpoint Create POST ThingDto /things"
        thingRouter shouldContain "endpoint BulkCreate POST ThingDto[] /things/bulk"
        thingRouter shouldContain "endpoint GetOne GET /things/{id"
        thingRouter shouldContain "endpoint Delete DELETE /things/{id"

        // ThingDto (a Java record) is detected and inlined as a controller-local type.
        thingRouter shouldContain "type ThingDto"

        // Method references compiled to INVOKEDYNAMIC LambdaMetafactory don't
        // leak the synthetic HandlerFunction interface name as a Wirespec type.
        listOf("HandlerFunction", "ServerRequest", "ServerResponse", "Mono").forEach { fw ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$fw\\b").containsMatchIn(thingRouter)) {
                "Spring framework type $fw leaked as a Wirespec definition:\n$thingRouter"
            }
        }
    }

    private operator fun File.div(child: String): File = File(this, child)
}
