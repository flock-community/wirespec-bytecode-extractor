package community.flock.wirespec.extractor

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class JaxRsExtractionTest {

    private fun jaxrsClassesDir(): File {
        val probe = community.flock.wirespec.extractor.fixtures.jaxrs.UserResource::class.java
        return File(probe.protectionDomain.codeSource.location.toURI())
    }

    @Test
    fun `extracts a ws file from a JAX-RS resource driven by swagger annotations`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(jaxrsClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.jaxrs",
            )
        )

        result.filesWritten.map { it.name } shouldContain "UserResource.ws"

        val ws = result.filesWritten.single { it.name == "UserResource.ws" }.readText()
        // Routing recovered from JAX-RS annotations.
        ws shouldContain "endpoint ListUsers GET /users"
        ws shouldContain "GET /users/{id"
        // Wirespec renders the request body between the method and the path.
        ws shouldContain "endpoint CreateUser POST CreateUserDto /users"
        ws shouldContain "DELETE /users/{id"
        // Multi-status responses recovered from swagger @ApiResponses.
        ws shouldContain "200"
        ws shouldContain "UserDto"
        ws shouldContain "404"
        ws shouldContain "ErrorDto"
    }

    @Test
    fun `extracts a ws file from a JAX-RS resource declared as a fun interface`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(jaxrsClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.jaxrs",
            )
        )

        result.filesWritten.map { it.name } shouldContain "SomeApi.ws"

        val ws = result.filesWritten.single { it.name == "SomeApi.ws" }.readText()
        ws shouldContain "endpoint GetSome GET /api"
        ws shouldContain "UserDto"
    }

    @Test
    fun `extractOpenApi=false skips JAX-RS resources`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(jaxrsClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.jaxrs",
                extractOpenApi = false,
            )
        )
        result.filesWritten.map { it.name }.contains("UserResource.ws") shouldBe false
    }

    @Test
    fun `extractSpring=false still extracts JAX-RS resources`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(jaxrsClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.jaxrs",
                extractSpring = false,
            )
        )
        result.filesWritten.map { it.name } shouldContain "UserResource.ws"
    }

    @Test
    fun `JAX-RS extraction no-ops for a package without resources`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        // The plain Spring fixtures package contains no JAX-RS resources; extraction
        // must still succeed and simply produce no UserResource.ws.
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(jaxrsClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.generic",
            )
        )
        result.filesWritten.map { it.name }.contains("UserResource.ws") shouldBe false
    }
}
