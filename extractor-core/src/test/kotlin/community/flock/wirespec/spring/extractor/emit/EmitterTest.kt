// src/test/kotlin/community/flock/wirespec/spring/extractor/emit/EmitterTest.kt
package community.flock.wirespec.spring.extractor.emit

import community.flock.wirespec.spring.extractor.ast.WirespecAstBuilder
import community.flock.wirespec.spring.extractor.model.Endpoint
import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.spring.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.spring.extractor.model.Param
import community.flock.wirespec.spring.extractor.model.WireType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText

class EmitterTest {

    private val builder = WirespecAstBuilder()
    private val emitter = Emitter()

    @Test
    fun `writes one ws file per controller and a shared types ws`(@TempDir dir: Path) {
        val ep = builder.toEndpoint(Endpoint(
            controllerSimpleName = "HelloController",
            name = "Hello",
            method = HttpMethod.GET,
            pathSegments = listOf(PathSegment.Literal("hello")),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responses = listOf(Endpoint.Response(200, WireType.Primitive(WireType.Primitive.Kind.STRING))),
        ))
        val typeDef = builder.toDefinition(WireType.Object(
            name = "UserDto",
            fields = listOf(WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING))),
        ))

        emitter.write(
            outputDir = dir.toFile(),
            controllerDefinitions = mapOf("HelloController" to listOf(ep)),
            sharedTypes = listOf(typeDef),
        )

        val files = dir.toFile().listFiles()!!.toList()
        files.map { it.name }.sorted() shouldHaveSize 2
        val hello = File(dir.toFile(), "HelloController.ws").readText()
        hello shouldContain "endpoint Hello GET /hello"
        val types = File(dir.toFile(), "types.ws").readText()
        types shouldContain "type UserDto"
    }

    @Test
    fun `field names starting with underscore are backticked in emitted ws`(@TempDir dir: Path) {
        val typeDef = builder.toDefinition(WireType.Object(
            name = "UnderscoreDto",
            fields = listOf(
                WireType.Field("_id", WireType.Primitive(WireType.Primitive.Kind.STRING)),
                WireType.Field("_private", WireType.Primitive(WireType.Primitive.Kind.STRING)),
                WireType.Field("normal", WireType.Primitive(WireType.Primitive.Kind.STRING)),
            ),
        ))
        emitter.write(dir.toFile(), emptyMap(), listOf(typeDef))
        val types = File(dir.toFile(), "types.ws").readText()

        types shouldContain "`_id`"
        types shouldContain "`_private`"
        // normal lowercase fields must NOT be backticked.
        types shouldContain "normal"
        types shouldNotContain "`normal`"
    }

    @Test
    fun `field names starting with capital are backticked in emitted ws`(@TempDir dir: Path) {
        val typeDef = builder.toDefinition(WireType.Object(
            name = "CapsDto",
            fields = listOf(
                WireType.Field("UserId", WireType.Primitive(WireType.Primitive.Kind.STRING)),
                WireType.Field("normal", WireType.Primitive(WireType.Primitive.Kind.STRING)),
            ),
        ))
        emitter.write(dir.toFile(), emptyMap(), listOf(typeDef))
        val types = File(dir.toFile(), "types.ws").readText()

        types shouldContain "`UserId`"
        types shouldContain "normal"
        types shouldNotContain "`normal`"
    }

    @Test
    fun `query and header param names starting with underscore are backticked`(@TempDir dir: Path) {
        val ep = builder.toEndpoint(Endpoint(
            controllerSimpleName = "ParamCtl",
            name = "GetWeird",
            method = HttpMethod.GET,
            pathSegments = listOf(PathSegment.Literal("weird")),
            queryParams = listOf(Param("_filter", Param.Source.QUERY, WireType.Primitive(WireType.Primitive.Kind.STRING))),
            headerParams = listOf(Param("_X-Hdr", Param.Source.HEADER, WireType.Primitive(WireType.Primitive.Kind.STRING))),
            cookieParams = emptyList(),
            requestBody = null,
            responses = listOf(Endpoint.Response(204, null)),
        ))
        emitter.write(
            outputDir = dir.toFile(),
            controllerDefinitions = mapOf("ParamCtl" to listOf(ep)),
            sharedTypes = emptyList(),
        )
        val out = File(dir.toFile(), "ParamCtl.ws").readText()

        out shouldContain "`_filter`"
        out shouldContain "`_X-Hdr`"
    }

    @Test
    fun `deletes existing ws files but leaves other files alone`(@TempDir dir: Path) {
        File(dir.toFile(), "stale.ws").writeText("// stale")
        val keepMe = File(dir.toFile(), "README.md").apply { writeText("keep") }

        emitter.write(outputDir = dir.toFile(), controllerDefinitions = emptyMap(), sharedTypes = emptyList())

        File(dir.toFile(), "stale.ws").exists() shouldBe false
        keepMe.exists() shouldBe true
    }

    @Test
    fun `write returns the list of files it wrote`(@TempDir dir: Path) {
        val ep = builder.toEndpoint(Endpoint(
            controllerSimpleName = "HelloController",
            name = "Hello",
            method = HttpMethod.GET,
            pathSegments = listOf(PathSegment.Literal("hello")),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responses = listOf(Endpoint.Response(200, WireType.Primitive(WireType.Primitive.Kind.STRING))),
        ))
        val typeDef = builder.toDefinition(WireType.Object(
            name = "UserDto",
            fields = listOf(WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING))),
        ))

        val written: List<File> = emitter.write(
            outputDir = dir.toFile(),
            controllerDefinitions = mapOf("HelloController" to listOf(ep)),
            sharedTypes = listOf(typeDef),
        )

        written.map { it.name }.sorted() shouldBe listOf("HelloController.ws", "types.ws")
        written.all { it.exists() } shouldBe true
    }

    @Test
    fun `multi-response endpoint emits one response clause per status`(@TempDir dir: Path) {
        val ep = builder.toEndpoint(Endpoint(
            controllerSimpleName = "MultiCtl",
            name = "GetUser",
            method = HttpMethod.GET,
            pathSegments = listOf(PathSegment.Literal("users")),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responses = listOf(
                Endpoint.Response(200, WireType.Ref("UserDto")),
                Endpoint.Response(404, WireType.Ref("ErrorDto")),
            ),
        ))
        emitter.write(
            outputDir = dir.toFile(),
            controllerDefinitions = mapOf("MultiCtl" to listOf(ep)),
            sharedTypes = emptyList(),
        )
        val out = File(dir.toFile(), "MultiCtl.ws").readText()
        out shouldContain "200"
        out shouldContain "UserDto"
        out shouldContain "404"
        out shouldContain "ErrorDto"
    }

    @Test
    fun `write returns empty list when nothing to emit`(@TempDir dir: Path) {
        val written: List<File> = emitter.write(
            outputDir = dir.toFile(),
            controllerDefinitions = emptyMap(),
            sharedTypes = emptyList(),
        )
        written shouldBe emptyList()
    }
}
