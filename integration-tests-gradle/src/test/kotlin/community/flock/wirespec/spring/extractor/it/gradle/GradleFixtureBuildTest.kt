package community.flock.wirespec.spring.extractor.it.gradle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories

/**
 * Drives Gradle (via TestKit) against each fixture project under `src/it/`.
 * Equivalent of the Maven IT's `FixtureBuildTest`:
 *   1. Copy the fixture into a sandbox under `build/it-work/`.
 *   2. Substitute `@project.version@` (plugin version) and `@itRepo@` (URI
 *      of the it-repo where Gradle should find the plugin marker) into the
 *      copied build files.
 *   3. Run `assemble` against an isolated TestKit/Gradle user home.
 *   4. Run the fixture-specific verifier on the produced `.ws` files.
 */
class GradleFixtureBuildTest {

    private val pluginVersion = sysProp("it.pluginVersion")
    private val itRepo = File(sysProp("it.repo"))
    private val fixturesRoot = File(sysProp("it.fixturesRoot"))
    private val workRoot = File(sysProp("it.workRoot"))
    private val testKitDir = File(sysProp("it.testKitDir"))

    @TestFactory
    fun `each fixture builds and produces expected wirespec output`(): List<DynamicTest> {
        val fixtures = fixturesRoot.listFiles { f -> f.isDirectory && File(f, "settings.gradle.kts").isFile }
            ?.sortedBy { it.name }
            ?: error("No fixtures under $fixturesRoot")

        return fixtures.map { fixture ->
            DynamicTest.dynamicTest(fixture.name) { runFixture(fixture) }
        }
    }

    private fun runFixture(fixture: File) {
        val workDir = File(workRoot, fixture.name).also {
            it.deleteRecursively()
            it.mkdirs()
        }
        copyFixture(fixture.toPath(), workDir.toPath())
        substituteTokens(
            workDir,
            mapOf(
                "@project.version@" to pluginVersion,
                "@itRepo@" to itRepo.toURI().toString(),
            ),
        )

        val result = GradleRunner.create()
            .withProjectDir(workDir)
            .withTestKitDir(testKitDir)            // isolated; never touches the user's ~/.gradle
            .withArguments("assemble", "--stacktrace")
            .forwardOutput()
            .build()

        assertTrue(result.output.isNotEmpty()) { "Gradle produced no output for ${fixture.name}" }

        when (fixture.name) {
            "basic-kotlin-app" -> verifyBasicKotlinApp(workDir)
            "basic-spring-app" -> verifyBasicSpringApp(workDir)
            "dsl-webflux-app"  -> DslFixtureVerifiers.verifyWebFluxApp(File(workDir, "build/wirespec"))
            "dsl-mvc-app"      -> DslFixtureVerifiers.verifyMvcApp(File(workDir, "build/wirespec"))
            "dsl-java-app"     -> DslFixtureVerifiers.verifyJavaApp(File(workDir, "build/wirespec"))
            "kafka-app"        -> KafkaFixtureVerifier.verify(File(workDir, "build/wirespec"))
            else -> error("No verifier registered for fixture ${fixture.name}")
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun copyFixture(src: Path, dest: Path) {
        dest.createDirectories()
        src.copyToRecursively(dest, followLinks = false, overwrite = true)
    }

    /**
     * Walk the work dir and substitute every occurrence of each token in any
     * `settings.gradle.kts` / `build.gradle.kts`. Files without matches are
     * left untouched.
     */
    private fun substituteTokens(workDir: File, tokens: Map<String, String>) {
        workDir.walkTopDown()
            .filter { it.isFile && (it.name == "settings.gradle.kts" || it.name == "build.gradle.kts") }
            .forEach { file ->
                val original = file.readText()
                var patched = original
                for ((k, v) in tokens) patched = patched.replace(k, v)
                if (patched != original) file.writeText(patched)
            }
    }

    // ---- Fixture-specific verifiers: ports of the Maven IT verifiers, with
    // ---- `target/wirespec` → `build/wirespec`. Duplicated for now; can be
    // ---- shared with the Maven IT module later via java-test-fixtures if
    // ---- the duplication becomes painful.

    private fun verifyBasicKotlinApp(workDir: File) {
        val wsDir = File(workDir, "build/wirespec")
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly("AdminController.ws", "UserController.ws", "types.ws")

        val controller = File(wsDir, "UserController.ws").readText()
        controller shouldContain "endpoint GetUser GET /users/{id"
        controller shouldContain "endpoint CreateUser POST"
        controller shouldMatch Regex("(?s).*endpoint ListUsers GET /users\\b.*")
        controller shouldMatch Regex("(?s).*200 -> UserDto\\[].*")
        controller shouldMatch Regex("(?s).*endpoint DeleteUser DELETE /users/\\{id.*")
        controller shouldMatch Regex("(?s).*204 -> Unit.*")

        val admin = File(wsDir, "AdminController.ws").readText()
        admin shouldContain "endpoint ListByRole GET /admins/by-role"

        // UserDto and UserDtoPage are shared (Page<UserDto> referenced by both controllers,
        // and UserDtoPage transitively references UserDto), so both live in types.ws.
        val types = File(wsDir, "types.ws").readText()
        types shouldContain "Role"
        types shouldContain "type UserDto"
        types shouldContain "type UserDtoPage"

        // Field shapes are asserted against the shared UserDto definition in types.ws.
        types shouldMatch Regex("(?s).*id\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*nickname\\s*:\\s*String\\?.*")
        types shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*balance\\s*:\\s*String\\b(?!\\?).*")
        types shouldContain "`_internalId`"
        types shouldContain "`SystemKey`"
        assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(types)) {
            "_internalId appears un-backticked at line start in types.ws:\n$types"
        }

        // The shared types must NOT appear in either per-controller file.
        assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(controller)) {
            "UserDto leaked into UserController.ws despite being shared via UserDtoPage:\n$controller"
        }
        assertTrue(!Regex("(?m)^\\s*type\\s+UserDtoPage\\b").containsMatchIn(controller)) {
            "UserDtoPage leaked into UserController.ws despite being shared:\n$controller"
        }

        // Both controllers expose the Page<UserDto>-flattening endpoint.
        controller shouldContain "endpoint Page GET /users/page"
        admin shouldContain "endpoint AdminPage GET /admins/page"

        // The raw `Page` type never appears anywhere.
        val combined = controller + "\n" + types + "\n" + admin
        assertTrue(!Regex("(?m)^\\s*type\\s+Page\\b").containsMatchIn(combined)) {
            "Raw Page type leaked into a .ws file:\n$combined"
        }

        listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(combined)) {
                "JDK type $jdk leaked into a .ws file:\n$combined"
            }
        }
        listOf("Continuation", "CoroutineContext").forEach { ktInternal ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$ktInternal\\b").containsMatchIn(combined)) {
                "Kotlin coroutine type $ktInternal leaked into a .ws file:\n$combined"
            }
        }
    }

    private fun verifyBasicSpringApp(workDir: File) {
        val wsDir = File(workDir, "build/wirespec")
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly("AdminController.ws", "UserController.ws", "types.ws")

        val controller = File(wsDir, "UserController.ws").readText()
        controller shouldContain "endpoint GetUser GET /users/{id"
        controller shouldContain "endpoint CreateUser POST"

        val admin = File(wsDir, "AdminController.ws").readText()
        admin shouldContain "endpoint ListByRole GET /admins/by-role"

        // UserDto and UserDtoPage are shared (Page<UserDto> referenced by both controllers,
        // and UserDtoPage transitively references UserDto), so both live in types.ws.
        val types = File(wsDir, "types.ws").readText()
        types shouldContain "Role"
        types shouldContain "type UserDto"
        types shouldContain "type UserDtoPage"

        // Field shapes asserted against the shared UserDto definition in types.ws.
        // Java records are all-nullable, so accept both `String` and `String?`.
        types shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\??.*")
        types shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\??.*")
        types shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\??.*")
        types shouldMatch Regex("(?s).*balance\\s*:\\s*String\\??.*")
        types shouldContain "`_internalId`"
        types shouldContain "`SystemKey`"
        assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(types)) {
            "_internalId appears un-backticked at line start in types.ws:\n$types"
        }

        assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(controller)) {
            "UserDto leaked into UserController.ws despite being shared via UserDtoPage:\n$controller"
        }
        assertTrue(!Regex("(?m)^\\s*type\\s+UserDtoPage\\b").containsMatchIn(controller)) {
            "UserDtoPage leaked into UserController.ws despite being shared:\n$controller"
        }

        // Both controllers expose the Page<UserDto>-flattening endpoint.
        controller shouldContain "endpoint Page GET /users/page"
        admin shouldContain "endpoint AdminPage GET /admins/page"

        val combined = controller + "\n" + types + "\n" + admin
        assertTrue(!Regex("(?m)^\\s*type\\s+Page\\b").containsMatchIn(combined)) {
            "Raw Page type leaked into a .ws file:\n$combined"
        }

        listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(combined)) {
                "JDK type $jdk leaked into a .ws file:\n$combined"
            }
        }
    }

    private fun sysProp(key: String): String =
        System.getProperty(key) ?: error("System property '$key' not set")
}
