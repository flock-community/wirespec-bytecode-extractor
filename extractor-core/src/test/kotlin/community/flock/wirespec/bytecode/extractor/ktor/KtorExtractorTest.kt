package community.flock.wirespec.bytecode.extractor.ktor

import community.flock.wirespec.bytecode.extractor.ExtractConfig
import community.flock.wirespec.bytecode.extractor.WirespecExtractor
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class KtorExtractorTest {

    private fun thisModuleClassesDirs(): List<File> {
        val probe = community.flock.wirespec.bytecode.extractor.fixtures.ktor.KtorUserClient::class.java
        val kotlinDir = File(probe.protectionDomain.codeSource.location.toURI())
        val javaDir = kotlinDir.parentFile?.parentFile?.resolve("java/test")
        return listOfNotNull(kotlinDir, javaDir?.takeIf { it.exists() })
    }

    private fun extract(tmp: Path): List<File> =
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = thisModuleClassesDirs(),
                runtimeClasspath = emptyList(),
                outputDirectory = File(tmp.toFile(), "ws").apply { mkdirs() },
                basePackage = "community.flock.wirespec.bytecode.extractor.fixtures.ktor",
            )
        ).filesWritten

    @Test
    fun `extracts Ktor server routing into a ws file`(@TempDir tmp: Path) {
        val files = extract(tmp)
        files.map { it.name } shouldContainAll listOf("KtorServerRoutesKt.ws")

        val ws = files.single { it.name == "KtorServerRoutesKt.ws" }.readText()
        // Routes (paths + methods), including nesting and an inline-path verb.
        ws shouldContain "GET /health"
        ws shouldContain "GET /users"
        ws shouldContain "POST UserDto /users"
        ws shouldContain "GET /users/{id"
        ws shouldContain "DELETE /users/{id"
        // Request body recovered from call.receive<UserDto>().
        ws shouldContain "UserDto"
        // Explicit status codes from HttpStatusCode.Created / NoContent.
        ws shouldContain "201"
        ws shouldContain "204"
        // Exactly the five declared routes — no spurious prefix-less duplicates
        // from walking the synthetic config lambdas at the top level.
        ws.split("endpoint ").size - 1 shouldBe 5
        ws shouldContain "endpoint GetUsersById GET /users/{id"
        ws shouldContain "endpoint DeleteUsersById DELETE /users/{id"
    }

    @Test
    fun `extracts Ktor client calls into a ws file`(@TempDir tmp: Path) {
        val files = extract(tmp)
        files.map { it.name } shouldContainAll listOf("KtorUserClient.ws")

        val ws = files.single { it.name == "KtorUserClient.ws" }.readText()
        ws shouldContain "GET /users"
        ws shouldContain "POST UserDto /users"
        // Response/request bodies recovered from .body() and setBody().
        ws shouldContain "UserDto"
        // One endpoint per client method: listUsers, getUser, createUser.
        ws.split("endpoint ").size - 1 shouldBe 3
        ws shouldContain "endpoint ListUsers GET /users"
        ws shouldContain "endpoint CreateUser POST UserDto /users"
    }

    @Test
    fun `extractKtor=false skips Ktor server and client`(@TempDir tmp: Path) {
        val files = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = thisModuleClassesDirs(),
                runtimeClasspath = emptyList(),
                outputDirectory = File(tmp.toFile(), "ws").apply { mkdirs() },
                basePackage = "community.flock.wirespec.bytecode.extractor.fixtures.ktor",
                extractKtor = false,
            )
        ).filesWritten
        val names = files.map { it.name }
        names.contains("KtorServerRoutesKt.ws") shouldBe false
        names.contains("KtorUserClient.ws") shouldBe false
    }
}
