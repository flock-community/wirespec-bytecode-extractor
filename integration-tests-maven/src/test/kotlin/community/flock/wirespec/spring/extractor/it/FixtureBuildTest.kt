package community.flock.wirespec.spring.extractor.it

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Drives `mvn` against each fixture project under `src/it/`. Replaces what the
 * `maven-invoker-plugin` did in the original Maven build:
 *   1. Copy the fixture into a sandbox under `build/it-work/`.
 *   2. Substitute `@project.version@` in the copied pom.xml with the actual plugin version.
 *   3. Run the goals from the fixture's `invoker.properties` against an isolated
 *      Maven local repo (the IT-local repo, which already contains our freshly
 *      published plugin).
 *   4. Run the fixture-specific verifier (Kotlin port of the original `verify.groovy`).
 */
class FixtureBuildTest {

    private val pluginVersion = System.getProperty("it.pluginVersion")
        ?: error("it.pluginVersion system property not set")
    private val itRepo = File(System.getProperty("it.repo") ?: error("it.repo not set"))
    private val fixturesRoot = File(System.getProperty("it.fixturesRoot") ?: error("it.fixturesRoot not set"))
    private val workRoot = File(System.getProperty("it.workRoot") ?: error("it.workRoot not set"))

    /**
     * Generated Maven user settings: declares `build/it-repo` as a remote
     * repository so Maven uses `maven-metadata.xml` to resolve the timestamped
     * snapshot Gradle published. (Maven's local-repo layout expects non-
     * timestamped snapshot filenames, which Gradle's `maven-publish` does not
     * produce for a `maven { url = ... }` target.)
     */
    private val settingsXml: File by lazy {
        val file = File(workRoot, "settings.xml").also { it.parentFile.mkdirs() }
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
              <profiles>
                <profile>
                  <id>it</id>
                  <repositories>
                    <repository>
                      <id>it-local</id>
                      <url>${itRepo.toURI()}</url>
                      <snapshots><enabled>true</enabled></snapshots>
                    </repository>
                  </repositories>
                  <pluginRepositories>
                    <pluginRepository>
                      <id>it-local</id>
                      <url>${itRepo.toURI()}</url>
                      <snapshots><enabled>true</enabled></snapshots>
                    </pluginRepository>
                  </pluginRepositories>
                </profile>
              </profiles>
              <activeProfiles>
                <activeProfile>it</activeProfile>
              </activeProfiles>
            </settings>
            """.trimIndent(),
        )
        file
    }

    /** Isolated local repo, so test runs don't depend on or pollute the user's ~/.m2. */
    private val mavenLocalRepo: File by lazy {
        File(workRoot, "m2-cache").also { it.mkdirs() }
    }

    @TestFactory
    fun `each fixture builds and produces expected wirespec output`(): List<DynamicTest> {
        val fixtures = fixturesRoot.listFiles { f -> f.isDirectory && File(f, "pom.xml").isFile }
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
        substituteVersion(File(workDir, "pom.xml").toPath())

        val invokerProps = File(fixture, "invoker.properties")
        val goals = parseInvokerGoals(invokerProps)

        val result = runMaven(workDir, goals)
        assertTrue(result.exitCode == 0) {
            "mvn ${goals.joinToString(" ")} failed for ${fixture.name} (exit ${result.exitCode}):\n" +
                "--- STDOUT ---\n${result.stdout}\n--- STDERR ---\n${result.stderr}"
        }

        when (fixture.name) {
            "basic-kotlin-app" -> verifyBasicKotlinApp(workDir)
            "basic-spring-app" -> verifyBasicSpringApp(workDir)
            "dsl-webflux-app"  -> DslFixtureVerifiers.verifyWebFluxApp(File(workDir, "target/wirespec"))
            "dsl-mvc-app"      -> DslFixtureVerifiers.verifyMvcApp(File(workDir, "target/wirespec"))
            "dsl-java-app"     -> DslFixtureVerifiers.verifyJavaApp(File(workDir, "target/wirespec"))
            else -> error("No verifier registered for fixture ${fixture.name}")
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun copyFixture(src: Path, dest: Path) {
        dest.createDirectories()
        src.copyToRecursively(dest, followLinks = false, overwrite = true)
    }

    private fun substituteVersion(pom: Path) {
        val original = pom.readText()
        val patched = original.replace("@project.version@", pluginVersion)
        if (patched != original) pom.writeText(patched)
    }

    /** Minimal parser: only honors `invoker.goals = a b c`. */
    private fun parseInvokerGoals(propsFile: File): List<String> {
        if (!propsFile.isFile) return listOf("clean", "compile", "wirespec:extract")
        val line = propsFile.readLines().firstOrNull { it.trim().startsWith("invoker.goals") }
            ?: return listOf("clean", "compile", "wirespec:extract")
        return line.substringAfter("=").trim().split(Regex("\\s+"))
    }

    private data class MvnResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runMaven(workDir: File, goals: List<String>): MvnResult {
        val mvn = locateMvn()
        val cmd = buildList {
            add(mvn)
            add("--batch-mode")
            add("--no-transfer-progress")
            add("-s"); add(settingsXml.absolutePath)
            add("-Dmaven.repo.local=${mavenLocalRepo.absolutePath}")
            addAll(goals)
        }

        val proc = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(false)
            .start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return MvnResult(exit, stdout, stderr)
    }

    private fun locateMvn(): String {
        System.getenv("MAVEN_HOME")?.let { home ->
            val candidate = File(home, "bin/mvn")
            if (candidate.canExecute()) return candidate.absolutePath
        }
        // Fall back to PATH lookup via `command -v`.
        val pathSep = File.pathSeparator
        val path = System.getenv("PATH") ?: ""
        path.split(pathSep).forEach { dir ->
            val candidate = File(dir, "mvn")
            if (candidate.canExecute()) return candidate.absolutePath
        }
        error("`mvn` not found via MAVEN_HOME or PATH; integration tests require Maven on PATH.")
    }

    // ---- Fixture-specific verifiers: Kotlin ports of the original verify.groovy ----

    private fun verifyBasicKotlinApp(workDir: File) {
        val wsDir = File(workDir, "target/wirespec")
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

        // Kotlin nullability + JDK-value-to-String + backticked names — all asserted
        // against the shared UserDto in types.ws now.
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

        assertTrue(!Regex("(?m)^\\s*type\\s+UserDto\\b").containsMatchIn(controller)) {
            "UserDto leaked into UserController.ws despite being shared via UserDtoPage:\n$controller"
        }
        assertTrue(!Regex("(?m)^\\s*type\\s+UserDtoPage\\b").containsMatchIn(controller)) {
            "UserDtoPage leaked into UserController.ws despite being shared:\n$controller"
        }

        // Both controllers expose the Page<UserDto>-flattening endpoint.
        controller shouldContain "endpoint Page GET /users/page"
        admin shouldContain "endpoint AdminPage GET /admins/page"

        // JDK class names must not leak as Wirespec definitions in any file.
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
        val wsDir = File(workDir, "target/wirespec")
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

        // Field shapes against the shared UserDto in types.ws.
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
}
