# Extractor Core Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the single `plugin/` Gradle module into `extractor-core` (Maven-agnostic library) and `extractor-maven-plugin` (thin Maven shim), preserving every existing behavior and the published Maven plugin artifact's coordinates.

**Architecture:** All Maven-agnostic source moves to a new `extractor-core` module that exposes one public entry point `WirespecExtractor.extract(ExtractConfig): ExtractResult`. The Maven plugin becomes a ~30-line shim that maps `MavenProject` onto `ExtractConfig`, adapts Maven `Log` to a core `ExtractLog`, and translates `WirespecExtractorException` to `MojoExecutionException`. The plugin's published groupId, artifactId, goal prefix, default phase, and `<extensions>true</extensions>` auto-bind behavior are unchanged.

**Tech Stack:** Kotlin 2.1.20, Gradle multi-module (Kotlin DSL), `org.gradlex.maven-plugin-development` 1.0.3, Maven plugin API 3.9.9, JUnit Jupiter 5.11.3, Kotest assertions 5.9.1. JVM 21 toolchain.

**Reference design spec:** `docs/superpowers/specs/2026-05-12-extractor-core-split-design.md`

---

## Prerequisites

The branch `migrate-to-gradle` currently has substantial uncommitted work (the Maven→Gradle migration). Before starting this plan:

- [ ] **Commit or stash the in-flight migration work** so the working tree is clean. The split plan starts from a clean working tree. Verify with `git status --short` returning empty.
- [ ] **Verify the baseline builds and tests pass** on the current state:

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL, no test failures. If anything is broken, fix that first — the plan below uses `./gradlew test` after every task as the regression check, and it must be reliable.

- [ ] **Verify the integration tests pass** as the strongest regression net:

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL. Both fixtures (`basic-kotlin-app`, `basic-spring-app`) produce their expected `.ws` output.

---

## Task 1: Rename `plugin/` to `extractor-maven-plugin/`

**Why first:** All subsequent tasks refer to file paths under `extractor-maven-plugin/`. Doing the rename first means stable paths through the rest of the plan and one focused, easily-reviewed commit for the directory move.

**Files:**
- Rename: `plugin/` → `extractor-maven-plugin/` (the whole directory tree)
- Modify: `settings.gradle.kts`
- Modify: `integration-tests/build.gradle.kts`
- Modify: `build.gradle.kts` (the root one — its comment references `plugin/`)

- [ ] **Step 1: Rename the directory in git**

```bash
git mv plugin extractor-maven-plugin
```

Verify with `git status --short` — should show a long list of renames (`R`) and nothing else.

- [ ] **Step 2: Update `settings.gradle.kts`**

Replace the contents with:

```kotlin
rootProject.name = "wirespec-extractor"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":extractor-maven-plugin")
include(":integration-tests")
```

(The only change is `:plugin` → `:extractor-maven-plugin`.)

- [ ] **Step 3: Update `integration-tests/build.gradle.kts`**

Find this line:

```kotlin
dependsOn(":plugin:publishMavenPublicationToItLocalRepository")
```

Replace with:

```kotlin
dependsOn(":extractor-maven-plugin:publishMavenPublicationToItLocalRepository")
```

- [ ] **Step 4: Update the root `build.gradle.kts` comment**

Find this comment:

```kotlin
// Per-module configuration
// lives in `plugin/build.gradle.kts` and `integration-tests/build.gradle.kts`.
```

Replace with:

```kotlin
// Per-module configuration lives in `extractor-maven-plugin/build.gradle.kts`,
// `extractor-core/build.gradle.kts`, and `integration-tests/build.gradle.kts`.
```

(The `extractor-core` reference is forward-looking — that module appears in Task 2.)

- [ ] **Step 5: Run unit tests to verify rename**

```bash
./gradlew :extractor-maven-plugin:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Run integration tests as the end-to-end regression check**

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL. (This task is a pure rename; behavior must be identical.)

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts integration-tests/build.gradle.kts build.gradle.kts
git add -u  # picks up the rename
git commit -m "refactor: rename plugin/ to extractor-maven-plugin/

Preparation for splitting the module into extractor-core +
extractor-maven-plugin. No behavior change."
```

---

## Task 2: Add `extractor-core/` module skeleton

**Goal:** Stand up an empty Kotlin library module so Task 3 can move files into it without first inventing the module on the fly.

**Files:**
- Create: `extractor-core/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create `extractor-core/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

description = "Spring → Wirespec extraction logic. Maven-agnostic; drives the Maven plugin."
base.archivesName.set("wirespec-extractor-core")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Spring's @PathVariable/@RequestParam (and our extractor) need parameter names.
        freeCompilerArgs.add("-java-parameters")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.classgraph)
    implementation(libs.wirespec.core)
    implementation(libs.wirespec.emitter)
    implementation(libs.spring.web)
    implementation(libs.spring.context)
    implementation(libs.jackson.annotations)
    implementation(libs.jakarta.validation)
    implementation(libs.swagger.annotations)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.reactor.core)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "wirespec-extractor-core"
            pom {
                name.set("Wirespec Extractor Core")
                description.set(project.description)
            }
        }
    }
    repositories {
        // Same build-local repo as the Maven plugin: integration tests can
        // resolve both artifacts from one place.
        maven {
            name = "itLocal"
            url = uri(rootProject.layout.buildDirectory.dir("it-repo"))
        }
    }
}
```

- [ ] **Step 2: Add `:extractor-core` to `settings.gradle.kts`**

Replace the `include` lines with:

```kotlin
include(":extractor-core")
include(":extractor-maven-plugin")
include(":integration-tests")
```

- [ ] **Step 3: Compile the new module to verify it's valid**

```bash
./gradlew :extractor-core:compileKotlin
```

Expected: BUILD SUCCESSFUL. (No source yet — this just verifies the module is wired.)

- [ ] **Step 4: Commit**

```bash
git add extractor-core/build.gradle.kts settings.gradle.kts
git commit -m "build: scaffold extractor-core module"
```

---

## Task 3: Move Maven-agnostic source and tests to `extractor-core`

**Goal:** Move every file in `extractor-maven-plugin/` that does not import `org.apache.maven.*` to `extractor-core/`, with no edits to file contents. Add `implementation(project(":extractor-core"))` to the plugin module and strip the dependencies that travelled with the moved code.

**Files moved (main):** the entire `classpath/`, `scan/`, `model/`, `ast/`, `extract/`, `emit/` subtrees under `src/main/kotlin/community/flock/wirespec/extractor/`.

**Files moved (test):** the test-side mirrors of those subtrees, plus the entire `fixtures/` subtree.

**Files staying in `extractor-maven-plugin/` (main):**
- `ExtractMojo.kt`
- `WirespecLifecycleParticipant.kt`
- `META-INF/plexus/components.xml`

**Files staying in `extractor-maven-plugin/` (test):**
- `ExtractMojoTest.kt`
- `WirespecLifecycleParticipantTest.kt`

- [ ] **Step 1: Move main source files via `git mv`**

Run each command from the repository root:

```bash
mkdir -p extractor-core/src/main/kotlin/community/flock/wirespec/extractor
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/classpath \
       extractor-core/src/main/kotlin/community/flock/wirespec/extractor/classpath
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/scan \
       extractor-core/src/main/kotlin/community/flock/wirespec/extractor/scan
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/model \
       extractor-core/src/main/kotlin/community/flock/wirespec/extractor/model
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ast \
       extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ast
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/extract \
       extractor-core/src/main/kotlin/community/flock/wirespec/extractor/extract
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/emit \
       extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit
```

- [ ] **Step 2: Move test source files via `git mv`**

```bash
mkdir -p extractor-core/src/test/kotlin/community/flock/wirespec/extractor
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/classpath \
       extractor-core/src/test/kotlin/community/flock/wirespec/extractor/classpath
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/scan \
       extractor-core/src/test/kotlin/community/flock/wirespec/extractor/scan
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ast \
       extractor-core/src/test/kotlin/community/flock/wirespec/extractor/ast
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/emit \
       extractor-core/src/test/kotlin/community/flock/wirespec/extractor/emit
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/extract \
       extractor-core/src/test/kotlin/community/flock/wirespec/extractor/extract
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/fixtures \
       extractor-core/src/test/kotlin/community/flock/wirespec/extractor/fixtures
```

- [ ] **Step 3: Verify the only remaining sources in `extractor-maven-plugin/src/`**

```bash
find extractor-maven-plugin/src -name "*.kt" | sort
find extractor-maven-plugin/src -name "*.xml" | sort
```

Expected output (exactly four files, two `.kt` main + two `.kt` test + one `.xml`):
```
extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt
extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/WirespecLifecycleParticipant.kt
extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ExtractMojoTest.kt
extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/WirespecLifecycleParticipantTest.kt
extractor-maven-plugin/src/main/resources/META-INF/plexus/components.xml
```

If anything else is left over, move it to `extractor-core/` using the same pattern (preserving its package path under `src/main/kotlin/community/flock/wirespec/extractor/` or `src/test/kotlin/.../`).

- [ ] **Step 4: Update `extractor-maven-plugin/build.gradle.kts` dependencies**

Find the `dependencies { ... }` block (currently around lines 85-116 of the pre-rename `plugin/build.gradle.kts`). Replace the entire block with:

```kotlin
dependencies {
    // Maven plugin API — provided at runtime by the Maven instance loading the plugin.
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.core)
    compileOnly(libs.maven.plugin.annotations)

    // Core extraction logic.
    implementation(project(":extractor-core"))

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Tests
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    // WirespecLifecycleParticipantTest uses org.apache.maven.model.* and MavenProject.
    testImplementation(libs.maven.core)
    testImplementation(libs.maven.plugin.api)
}
```

Removed dependencies (now provided transitively or moved to core):
- `implementation(libs.kotlin.reflect)` — moved with reflection-using code
- `implementation(libs.classgraph)` — moved with `ControllerScanner`
- `implementation(libs.wirespec.core)` and `libs.wirespec.emitter` — moved with `Emitter` and `WirespecAstBuilder`
- `implementation(libs.spring.web)`, `libs.spring.context` — moved with extractor logic
- `implementation(libs.jackson.annotations)`, `libs.jakarta.validation`, `libs.swagger.annotations` — moved with `extract/`
- `testImplementation(libs.reactor.core)` — moved with `ReturnTypeUnwrapperTest`

- [ ] **Step 5: Run all tests to verify the move is correct**

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL. Both modules' tests pass. The Kotlin same-package-across-modules rule means `ExtractMojo.kt` keeps working without any import changes — it sees `ClasspathBuilder`, `ControllerScanner`, etc. from `extractor-core` because both files declare the same parent package.

If you see "Unresolved reference: detectControllerCollisions" or similar, it means a file with these helpers got moved by accident. The orphan helpers in `ExtractMojo.kt` stay put until Task 5. Recheck Step 3's file list.

- [ ] **Step 6: Run integration tests**

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL. End-to-end behavior is unchanged.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Maven-agnostic source to extractor-core

Move classpath/, scan/, model/, ast/, extract/, emit/ subtrees
(main and test) plus the fixtures/ subtree from extractor-maven-plugin
into the new extractor-core module. Wire extractor-maven-plugin to
depend on :extractor-core; drop the dependencies that travelled with
the moved code.

ExtractMojo, WirespecLifecycleParticipant, components.xml, and their
two test files remain in extractor-maven-plugin. The orphan helpers
in ExtractMojo (detectControllerCollisions, effectiveBasePackage,
assertOutputWritable) are still in place — Task 5 moves them.

No behavior change."
```

---

## Task 4: Extend `Emitter.write` to return `List<File>`

**Why:** The new core `ExtractResult` needs the list of `.ws` files actually written. Today `Emitter.write` returns `Unit`. This is a small, focused change with one call site (the mojo's `execute()` body) that we update in the same commit.

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt`
- Modify: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt` (one-line: ignore the new return value, for now)

- [ ] **Step 1: Add a failing test in `EmitterTest.kt`**

Add this test at the end of the `EmitterTest` class, before the closing brace:

```kotlin
    @Test
    fun `write returns the list of files it wrote`(@TempDir dir: Path) {
        val ep = builder.toEndpoint(Endpoint(
            controllerSimpleName = "HelloController",
            name = "Hello",
            method = HttpMethod.GET,
            pathSegments = listOf(PathSegment.Literal("hello")),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responseBody = WireType.Primitive(WireType.Primitive.Kind.STRING),
            statusCode = 200,
        ))
        val typeDef = builder.toDefinition(WireType.Object(
            name = "UserDto",
            fields = listOf(WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING))),
        ))

        val written: List<File> = emitter.write(
            outputDir = dir.toFile(),
            controllerEndpoints = mapOf("HelloController" to listOf(ep)),
            sharedTypes = listOf(typeDef),
        )

        written.map { it.name }.sorted() shouldBe listOf("HelloController.ws", "types.ws")
        written.all { it.exists() } shouldBe true
    }

    @Test
    fun `write returns empty list when nothing to emit`(@TempDir dir: Path) {
        val written: List<File> = emitter.write(
            outputDir = dir.toFile(),
            controllerEndpoints = emptyMap(),
            sharedTypes = emptyList(),
        )
        written shouldBe emptyList()
    }
```

(Existing tests already exercise the file-writing side effects — these new tests focus on the return value.)

- [ ] **Step 2: Run the new tests to confirm they fail**

```bash
./gradlew :extractor-core:test --tests "community.flock.wirespec.extractor.emit.EmitterTest"
```

Expected: COMPILATION ERROR — `Type mismatch: inferred type is Unit but List<File> was expected`. (Or, if Kotlin's smart-cast quirks let the assignment through, FAIL on the `written.map` line.)

- [ ] **Step 3: Modify `Emitter.write` to return `List<File>`**

Open `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt` and replace the `write` function body (currently returns `Unit`):

```kotlin
    fun write(
        outputDir: File,
        controllerEndpoints: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ): List<File> {
        outputDir.mkdirs()
        clearExistingWs(outputDir)

        val written = mutableListOf<File>()

        controllerEndpoints.forEach { (controller, defs) ->
            defs.toNonEmptyListOrNull()?.let { nel ->
                val path = File(outputDir, "$controller.ws")
                path.writeText(render(nel, "$controller.ws"))
                written += path
            }
        }

        sharedTypes.toNonEmptyListOrNull()?.let { nel ->
            val path = File(outputDir, "types.ws")
            path.writeText(render(nel, "types.ws"))
            written += path
        }

        return written
    }
```

(Three small additions: return type annotation, `val written = mutableListOf<File>()`, two `written += path` lines, `return written`.)

- [ ] **Step 4: Update the mojo's single call site**

Open `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt`. Find this block (around line 84):

```kotlin
        Emitter().write(
            outputDir = output,
            controllerEndpoints = byController,
            sharedTypes = sharedTypes,
        )
```

No change required — the return value is ignored implicitly. Kotlin accepts discarding non-Unit returns from expression statements. Verify with a build (next step).

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. The new EmitterTest cases pass; everything else is unaffected.

- [ ] **Step 6: Run integration tests**

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt
git commit -m "refactor(emitter): return list of written .ws files

Emitter.write now returns List<File> instead of Unit so the upcoming
WirespecExtractor public API can populate ExtractResult.filesWritten.
The mojo's existing call site discards the return value implicitly;
no behavior change."
```

---

## Task 5: Introduce the `extractor-core` public API

**Goal:** Add `WirespecExtractor.extract(ExtractConfig): ExtractResult` plus its supporting types (`ExtractConfig`, `ExtractResult`, `ExtractLog`, `WirespecExtractorException`), with internal versions of the three orphan helpers from `ExtractMojo.kt`. Cover the public contract with a new `WirespecExtractorTest`. Existing `ExtractMojo` is not changed in this task (it still has its own copies of the helpers and uses them directly); Task 6 wires it through the new API.

**Files:**
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt`
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractConfig.kt`
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractResult.kt`
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractLog.kt`
- Create: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractorException.kt`
- Create: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt`

- [ ] **Step 1: Write the failing happy-path test**

Create `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt`:

```kotlin
package community.flock.wirespec.extractor

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WirespecExtractorTest {

    /** Locate the test-classes directory of this module on the runtime classpath. */
    private fun thisModuleClassesDir(): File {
        // Any fixture class works as a probe; HelloController is small and stable.
        val probe = community.flock.wirespec.extractor.fixtures.HelloController::class.java
        val classFileUrl = probe.protectionDomain.codeSource.location
        return File(classFileUrl.toURI())
    }

    @Test
    fun `extract writes ws files for known controllers and returns counts`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectory = thisModuleClassesDir(),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
            )
        )

        result.controllerCount shouldBe (out.listFiles()!!.filter { it.name != "types.ws" }.size)
        result.filesWritten.map { it.name } shouldContainAll listOf("HelloController.ws")
        result.filesWritten.all { it.exists() } shouldBe true
    }

    @Test
    fun `extract throws WirespecExtractorException when classes directory is missing`(@TempDir tmp: Path) {
        val missing = File(tmp.toFile(), "does-not-exist")
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }

        val ex = assertThrows<WirespecExtractorException> {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectory = missing,
                    runtimeClasspath = emptyList(),
                    outputDirectory = out,
                )
            )
        }
        ex.message!! shouldContain "No compiled classes in"
        ex.message!! shouldContain "Did `compile` run before `wirespec:extract`?"
    }

    @Test
    fun `extract routes log messages through the provided ExtractLog`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val log = object : ExtractLog {
            override fun info(msg: String) { infos += msg }
            override fun warn(msg: String) { warns += msg }
        }

        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectory = thisModuleClassesDir(),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
                log = log,
            )
        )

        infos.any { it.startsWith("Found ") && it.endsWith(" controller(s)") } shouldBe true
        infos.any { it.startsWith("Wrote ") && it.contains(".ws file(s) to") } shouldBe true
    }

    @Test
    fun `ExtractLog NoOp is the default and is safe`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        // Just verify the default is wired without throwing.
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectory = thisModuleClassesDir(),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
            )
        )
    }
}
```

- [ ] **Step 2: Run the new test to confirm it fails to compile (no API yet)**

```bash
./gradlew :extractor-core:test --tests "community.flock.wirespec.extractor.WirespecExtractorTest"
```

Expected: COMPILATION ERROR — `Unresolved reference: WirespecExtractor` (and `ExtractConfig`, `ExtractLog`, `WirespecExtractorException`).

- [ ] **Step 3: Create `WirespecExtractorException.kt`**

```kotlin
package community.flock.wirespec.extractor

/**
 * Thrown by [WirespecExtractor.extract] when extraction fails because of:
 * - a missing or empty classes directory
 * - a non-writable output directory
 * - two scanned controllers sharing a simple name
 */
class WirespecExtractorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

- [ ] **Step 4: Create `ExtractLog.kt`**

```kotlin
package community.flock.wirespec.extractor

/**
 * Logger sink consumed by [WirespecExtractor.extract]. Implementations bridge
 * this to whichever logging framework the host uses (Maven's `Log`, SLF4J,
 * stdout, etc.).
 */
interface ExtractLog {
    fun info(msg: String)
    fun warn(msg: String)

    object NoOp : ExtractLog {
        override fun info(msg: String) {}
        override fun warn(msg: String) {}
    }
}
```

- [ ] **Step 5: Create `ExtractConfig.kt`**

```kotlin
package community.flock.wirespec.extractor

import java.io.File

/**
 * Input to [WirespecExtractor.extract].
 *
 * @property classesDirectory  Directory of compiled `.class` files to scan
 *   (typically `target/classes` in a Maven project).
 * @property runtimeClasspath  Additional jars and directories needed so the
 *   class loader can resolve types referenced by scanned classes.
 * @property outputDirectory   Where `.ws` files will be written.
 * @property basePackage       Optional package prefix to restrict scanning;
 *   `null` or blank means scan every package.
 * @property log               Logger sink. Defaults to [ExtractLog.NoOp].
 */
data class ExtractConfig(
    val classesDirectory: File,
    val runtimeClasspath: List<File>,
    val outputDirectory: File,
    val basePackage: String? = null,
    val log: ExtractLog = ExtractLog.NoOp,
)
```

- [ ] **Step 6: Create `ExtractResult.kt`**

```kotlin
package community.flock.wirespec.extractor

import java.io.File

/**
 * Result of a successful [WirespecExtractor.extract] run.
 *
 * @property controllerCount  Number of `<Controller>.ws` files written.
 * @property sharedTypeCount  Number of definitions in the shared `types.ws`
 *   (0 means no `types.ws` was written).
 * @property filesWritten     Every `.ws` file written this run.
 */
data class ExtractResult(
    val controllerCount: Int,
    val sharedTypeCount: Int,
    val filesWritten: List<File>,
)
```

- [ ] **Step 7: Create `WirespecExtractor.kt`**

```kotlin
package community.flock.wirespec.extractor

import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.extractor.ast.WirespecAstBuilder
import community.flock.wirespec.extractor.classpath.ClasspathBuilder
import community.flock.wirespec.extractor.emit.Emitter
import community.flock.wirespec.extractor.extract.EndpointExtractor
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.scan.ControllerScanner
import java.io.File

/**
 * Maven-agnostic entry point for the Spring → Wirespec extractor.
 *
 * Scans [ExtractConfig.classesDirectory] for Spring controllers and writes
 * `<Controller>.ws` + (optional) `types.ws` files to
 * [ExtractConfig.outputDirectory].
 */
object WirespecExtractor {

    /**
     * @throws WirespecExtractorException if the classes directory is missing
     *   or empty, the output directory is not writable, or two controllers
     *   share a simple name.
     */
    fun extract(config: ExtractConfig): ExtractResult {
        val classesDir = config.classesDirectory
        if (!classesDir.exists() || classesDir.listFiles().isNullOrEmpty()) {
            throw WirespecExtractorException(
                "No compiled classes in ${classesDir.absolutePath}. Did `compile` run before `wirespec:extract`?"
            )
        }
        assertOutputWritable(config.outputDirectory)

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = config.runtimeClasspath.map { it.absolutePath },
            outputDirectory = classesDir,
        )

        val effectiveBasePackage = effectiveBasePackage(config.basePackage)
        val scanPackages = listOfNotNull(effectiveBasePackage)

        return ClasspathBuilder.fromUrls(urls, parent = javaClass.classLoader).use { loader ->
            val controllers = ControllerScanner.scan(
                loader, scanPackages, effectiveBasePackage,
                onWarn = { msg -> config.log.warn(msg) },
            )
            config.log.info("Found ${controllers.size} controller(s)")

            val collisions = detectControllerCollisions(controllers)
            if (collisions.isNotEmpty()) {
                val msg = collisions.entries.joinToString("; ") { (name, classes) ->
                    "$name in [${classes.joinToString(", ")}]"
                }
                throw WirespecExtractorException("Controller simple-name collisions: $msg")
            }

            val types = TypeExtractor()
            val endpoints = EndpointExtractor(types)
            val builder = WirespecAstBuilder()

            val byController = controllers.associate { c ->
                val eps = try {
                    endpoints.extract(c).map(builder::toEndpoint)
                } catch (t: Throwable) {
                    config.log.warn("Skipping ${c.name}: ${t.message}")
                    emptyList()
                }
                c.simpleName to eps.map { it as Definition }
            }.filterValues { it.isNotEmpty() }

            val sharedTypes = types.definitions.mapNotNull { def ->
                try {
                    builder.toDefinition(def)
                } catch (t: Throwable) {
                    config.log.warn("Skipping type ${def}: ${t.message}")
                    null
                }
            }

            val filesWritten = Emitter().write(
                outputDir = config.outputDirectory,
                controllerEndpoints = byController,
                sharedTypes = sharedTypes,
            )
            config.log.info(
                "Wrote ${byController.size + (if (sharedTypes.isEmpty()) 0 else 1)} .ws file(s) to ${config.outputDirectory.absolutePath}"
            )

            ExtractResult(
                controllerCount = byController.size,
                sharedTypeCount = sharedTypes.size,
                filesWritten = filesWritten,
            )
        }
    }
}

/** Returns a map of simple name -> list of FQNs for controllers sharing the same simple name. */
internal fun detectControllerCollisions(controllers: List<Class<*>>): Map<String, List<String>> =
    controllers.groupBy { it.simpleName }
        .filterValues { it.size > 1 }
        .mapValues { (_, classes) -> classes.map { it.name } }

/** Normalises the raw `basePackage` parameter: blank or null becomes null. */
internal fun effectiveBasePackage(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

/**
 * Asserts that [output] (or the nearest existing ancestor) is writable.
 * Throws [WirespecExtractorException] otherwise.
 */
internal fun assertOutputWritable(output: File) {
    val existing = generateSequence(output) { it.parentFile }.firstOrNull { it.exists() }
        ?: throw WirespecExtractorException("No writable ancestor for output: ${output.absolutePath}")
    if (!existing.canWrite()) {
        throw WirespecExtractorException("Output dir not writable: ${existing.absolutePath}")
    }
}
```

- [ ] **Step 8: Run the new test to verify it passes**

```bash
./gradlew :extractor-core:test --tests "community.flock.wirespec.extractor.WirespecExtractorTest"
```

Expected: BUILD SUCCESSFUL, four tests pass.

If the first test's `out.listFiles()!!.filter { it.name != "types.ws" }.size` assertion is flaky because the fixtures include controllers that emit nothing, replace it with the stronger but more explicit:

```kotlin
result.controllerCount shouldBe (result.filesWritten.count { it.name != "types.ws" })
```

- [ ] **Step 9: Run all tests to verify nothing else broke**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. The existing `ExtractMojoTest` (still in `extractor-maven-plugin`) still passes because it has its own private copies of the helpers in `ExtractMojo.kt` — the two copies coexist temporarily and will be reconciled in Task 6.

- [ ] **Step 10: Run integration tests**

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt \
        extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractConfig.kt \
        extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractResult.kt \
        extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractLog.kt \
        extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractorException.kt \
        extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt
git commit -m "feat(core): add WirespecExtractor public API

Introduce WirespecExtractor.extract(ExtractConfig): ExtractResult as
the Maven-agnostic entry point for the extractor. ExtractLog routes
log messages to whichever framework the host uses; ExtractLog.NoOp is
the safe default.

The orphan helpers (detectControllerCollisions, effectiveBasePackage,
assertOutputWritable) are now internal to extractor-core. ExtractMojo
still holds its own copies until Task 6 refactors it through the new
API."
```

---

## Task 6: Refactor `ExtractMojo` to a shim + add `MavenExtractLog` + trim `ExtractMojoTest`

**Goal:** Reduce `ExtractMojo.execute()` to a thin adapter that calls `WirespecExtractor.extract()`. Add a `MavenExtractLog` adapter so Maven's `Log` plugs into core's `ExtractLog`. Remove the now-duplicate helpers and helper-tests from `ExtractMojo.kt` / `ExtractMojoTest.kt`. Add the small set of adapter-layer tests the spec calls for.

**Files:**
- Modify: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt`
- Create: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/MavenExtractLog.kt`
- Modify: `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ExtractMojoTest.kt`

- [ ] **Step 1: Write the failing adapter test**

Replace the entire contents of `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ExtractMojoTest.kt` with:

```kotlin
package community.flock.wirespec.extractor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.maven.model.Build
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExtractMojoTest {

    @Test
    fun `execute translates WirespecExtractorException to MojoExecutionException`(@TempDir tmp: Path) {
        // A bare MavenProject() with no resolved dependencies makes
        // project.runtimeClasspathElements throw DependencyResolutionRequiredException;
        // the mojo catches that and uses an empty classpath. The missing classes
        // dir then triggers WirespecExtractorException, which the mojo translates.
        val project = MavenProject().apply {
            build = Build().apply { outputDirectory = File(tmp.toFile(), "missing-classes").absolutePath }
        }

        val mojo = ExtractMojo().apply {
            this.project = project
            this.output = File(tmp.toFile(), "wirespec").apply { mkdirs() }
            this.basePackage = null
        }

        val ex = assertThrows<MojoExecutionException> { mojo.execute() }
        ex.message!! shouldContain "No compiled classes in"
    }

    @Test
    fun `MavenExtractLog forwards info and warn to the wrapped Maven Log`() {
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val mavenLog = object : Log {
            override fun isDebugEnabled() = false
            override fun debug(content: CharSequence?) {}
            override fun debug(content: CharSequence?, error: Throwable?) {}
            override fun debug(error: Throwable?) {}
            override fun isInfoEnabled() = true
            override fun info(content: CharSequence?) { infos += content.toString() }
            override fun info(content: CharSequence?, error: Throwable?) { infos += content.toString() }
            override fun info(error: Throwable?) {}
            override fun isWarnEnabled() = true
            override fun warn(content: CharSequence?) { warns += content.toString() }
            override fun warn(content: CharSequence?, error: Throwable?) { warns += content.toString() }
            override fun warn(error: Throwable?) {}
            override fun isErrorEnabled() = true
            override fun error(content: CharSequence?) {}
            override fun error(content: CharSequence?, error: Throwable?) {}
            override fun error(error: Throwable?) {}
        }
        val adapter = MavenExtractLog(mavenLog)
        adapter.info("ping-info")
        adapter.warn("ping-warn")

        infos shouldBe listOf("ping-info")
        warns shouldBe listOf("ping-warn")
    }
}
```

The first test exercises the end-to-end adapter path (Maven types → ExtractConfig → core throws → shim re-throws as Maven exception). The second pins down the `MavenExtractLog` ↔ `Log` mapping.

This test won't compile yet — `MavenExtractLog` doesn't exist (Step 4 creates it), and `ExtractMojo` still has its old `execute()` body with the orphan helpers (Step 3 rewrites it). The compile error is exactly what we want at this point.

- [ ] **Step 2: Run the new tests to confirm they fail**

```bash
./gradlew :extractor-maven-plugin:test --tests "community.flock.wirespec.extractor.ExtractMojoTest"
```

Expected: COMPILATION ERROR — `Unresolved reference: MavenExtractLog` (and possibly other failures depending on `project.runtimeClasspathElements` resolution).

- [ ] **Step 3: Rewrite `ExtractMojo.kt` as the shim**

Replace the entire file `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt` with:

```kotlin
package community.flock.wirespec.extractor

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

@Mojo(
    name = "extract",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true,
)
class ExtractMojo : AbstractMojo() {

    @Parameter(required = true, property = "wirespec.output")
    lateinit var output: File

    @Parameter(property = "wirespec.basePackage")
    var basePackage: String? = null

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    override fun execute() {
        val runtimeClasspath: List<File> = try {
            project.runtimeClasspathElements.orEmpty().map(::File)
        } catch (_: Exception) {
            emptyList()
        }

        try {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectory = File(project.build.outputDirectory),
                    runtimeClasspath = runtimeClasspath,
                    outputDirectory = output,
                    basePackage = basePackage,
                    log = MavenExtractLog(log),
                )
            )
        } catch (e: WirespecExtractorException) {
            throw MojoExecutionException(e.message, e.cause)
        }
    }
}
```

The `try/catch` around `runtimeClasspathElements` matches what `MavenProject` does in unit tests where dependency resolution hasn't run: it throws `DependencyResolutionRequiredException`. In real Maven runs with `requiresDependencyResolution = RUNTIME`, the field is resolved before `execute()` is called.

The orphan helpers (`detectControllerCollisions`, `effectiveBasePackage`, `assertOutputWritable`) are gone from this file — they live in `extractor-core/.../WirespecExtractor.kt` from Task 5. The mojo no longer references them.

- [ ] **Step 4: Create `MavenExtractLog.kt`**

Create `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/MavenExtractLog.kt`:

```kotlin
package community.flock.wirespec.extractor

/**
 * Adapts Maven's plugin [org.apache.maven.plugin.logging.Log] to core's
 * [ExtractLog] sink.
 */
internal class MavenExtractLog(
    private val mavenLog: org.apache.maven.plugin.logging.Log,
) : ExtractLog {
    override fun info(msg: String) { mavenLog.info(msg) }
    override fun warn(msg: String) { mavenLog.warn(msg) }
}
```

- [ ] **Step 5: Run unit tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. The new `ExtractMojoTest` passes; the existing `WirespecLifecycleParticipantTest` still passes (it doesn't depend on the helpers); `WirespecExtractorTest` and all `extractor-core` tests still pass.

- [ ] **Step 6: Run integration tests — the strongest end-to-end check**

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL. Both fixtures produce byte-identical `.ws` output to what they produced before the refactor.

If a fixture fails with "No compiled classes…": that means `project.runtimeClasspathElements` isn't being read correctly. Double-check Step 3's `try/catch` block.

If a fixture fails with a different shape of `MojoExecutionException`: check that `WirespecExtractorException.message` and `.cause` are preserved through the rethrow.

- [ ] **Step 7: Commit**

```bash
git add extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt \
        extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/MavenExtractLog.kt \
        extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ExtractMojoTest.kt
git commit -m "refactor(mojo): make ExtractMojo a thin shim over WirespecExtractor

ExtractMojo.execute() now maps MavenProject onto ExtractConfig, calls
WirespecExtractor.extract(), and translates WirespecExtractorException
to MojoExecutionException. The orphan helpers that used to live in
this file moved to extractor-core in Task 5.

MavenExtractLog bridges Maven's Log to core's ExtractLog interface.

ExtractMojoTest is rewritten to cover the adapter layer only:
- MojoExecutionException translation when core throws
- MavenExtractLog ↔ Maven Log forwarding
The helper-level tests now live in WirespecExtractorTest in core.

No behavior change for Maven users."
```

---

## Task 7: Repackage Maven adapter classes to `…extractor.maven`

**Goal:** Move `ExtractMojo`, `WirespecLifecycleParticipant`, `MavenExtractLog`, and their tests into the `community.flock.wirespec.extractor.maven` subpackage. Update `META-INF/plexus/components.xml` to reference the new FQN of the lifecycle participant.

**Files:**
- Move: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt` → `.../extractor/maven/ExtractMojo.kt`
- Move: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/WirespecLifecycleParticipant.kt` → `.../extractor/maven/WirespecLifecycleParticipant.kt`
- Move: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/MavenExtractLog.kt` → `.../extractor/maven/MavenExtractLog.kt`
- Move: `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ExtractMojoTest.kt` → `.../extractor/maven/ExtractMojoTest.kt`
- Move: `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/WirespecLifecycleParticipantTest.kt` → `.../extractor/maven/WirespecLifecycleParticipantTest.kt`
- Modify: `extractor-maven-plugin/src/main/resources/META-INF/plexus/components.xml`

- [ ] **Step 1: Move the source files**

```bash
mkdir -p extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven
mkdir -p extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/maven

git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt \
       extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/ExtractMojo.kt
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/WirespecLifecycleParticipant.kt \
       extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/WirespecLifecycleParticipant.kt
git mv extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/MavenExtractLog.kt \
       extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/MavenExtractLog.kt
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/ExtractMojoTest.kt \
       extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/maven/ExtractMojoTest.kt
git mv extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/WirespecLifecycleParticipantTest.kt \
       extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/maven/WirespecLifecycleParticipantTest.kt
```

- [ ] **Step 2: Update package declarations in `ExtractMojo.kt`**

Open `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/ExtractMojo.kt`.

Find:
```kotlin
package community.flock.wirespec.extractor
```

Replace with:
```kotlin
package community.flock.wirespec.extractor.maven

import community.flock.wirespec.extractor.ExtractConfig
import community.flock.wirespec.extractor.WirespecExtractor
import community.flock.wirespec.extractor.WirespecExtractorException
```

(The imports for `ExtractConfig`, `WirespecExtractor`, `WirespecExtractorException` were implicit when the mojo was in the same package as them; now they need explicit imports.)

- [ ] **Step 3: Update package declaration in `WirespecLifecycleParticipant.kt`**

Open `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/WirespecLifecycleParticipant.kt`.

Find:
```kotlin
package community.flock.wirespec.extractor
```

Replace with:
```kotlin
package community.flock.wirespec.extractor.maven
```

No imports need to change — `WirespecLifecycleParticipant` only uses Maven types and Plexus annotations, all already imported.

- [ ] **Step 4: Update package declaration in `MavenExtractLog.kt`**

Open `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/MavenExtractLog.kt`.

Find:
```kotlin
package community.flock.wirespec.extractor
```

Replace with:
```kotlin
package community.flock.wirespec.extractor.maven

import community.flock.wirespec.extractor.ExtractLog
```

- [ ] **Step 5: Update package declaration in `ExtractMojoTest.kt`**

Open `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/maven/ExtractMojoTest.kt`.

Find:
```kotlin
package community.flock.wirespec.extractor
```

Replace with:
```kotlin
package community.flock.wirespec.extractor.maven

import community.flock.wirespec.extractor.WirespecExtractorException
```

(Only `WirespecExtractorException` needs an explicit import — references to `ExtractMojo` and `MavenExtractLog` resolve from the same package.)

- [ ] **Step 6: Update package declaration in `WirespecLifecycleParticipantTest.kt`**

Open `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/maven/WirespecLifecycleParticipantTest.kt`.

Find:
```kotlin
package community.flock.wirespec.extractor
```

Replace with:
```kotlin
package community.flock.wirespec.extractor.maven
```

No imports need to change — `WirespecLifecycleParticipant` is referenced unqualified and resolves from the same package.

- [ ] **Step 7: Update `components.xml`**

Open `extractor-maven-plugin/src/main/resources/META-INF/plexus/components.xml`.

Find:
```xml
<implementation>community.flock.wirespec.extractor.WirespecLifecycleParticipant</implementation>
```

Replace with:
```xml
<implementation>community.flock.wirespec.extractor.maven.WirespecLifecycleParticipant</implementation>
```

The `<role-hint>wirespec-extractor</role-hint>` stays unchanged.

- [ ] **Step 8: Run unit tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run integration tests — these are the only check that `components.xml` is wired correctly**

```bash
./gradlew :integration-tests:test
```

Expected: BUILD SUCCESSFUL. Both fixtures use `<extensions>true</extensions>` with no `<executions>` — if `components.xml` points at the wrong FQN, the lifecycle participant won't be discovered and the fixtures will produce no `.ws` files. `FixtureBuildTest` catches this immediately.

If a fixture fails with "wirespec output dir missing at …/target/wirespec": the lifecycle participant isn't being discovered. Verify Step 7's FQN exactly matches the new package + class name.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(maven): move Maven adapter classes to ...extractor.maven

ExtractMojo, WirespecLifecycleParticipant, MavenExtractLog, and their
tests now live in the community.flock.wirespec.extractor.maven
subpackage. components.xml updates the Plexus <implementation> FQN to
match.

Maven users see no change: same groupId, artifactId, goal prefix,
default phase, and <extensions>true</extensions> behavior."
```

---

## Task 8: Add `:extractor-core` publish dependency to integration tests + final end-to-end verification

**Goal:** The Maven plugin's published POM now declares `wirespec-extractor-core` as a runtime dependency. The integration-test Maven runs need that core artifact in the IT-local repo when they resolve the plugin. One line of build glue, then a full integration-test pass as the final regression check.

**Files:**
- Modify: `integration-tests/build.gradle.kts`

- [ ] **Step 1: Add the second `dependsOn` to `integration-tests/build.gradle.kts`**

Find:
```kotlin
dependsOn(":extractor-maven-plugin:publishMavenPublicationToItLocalRepository")
```

Replace with:
```kotlin
dependsOn(":extractor-maven-plugin:publishMavenPublicationToItLocalRepository")
dependsOn(":extractor-core:publishMavenPublicationToItLocalRepository")
```

- [ ] **Step 2: Run integration tests from a clean state**

```bash
./gradlew clean :integration-tests:test
```

Expected: BUILD SUCCESSFUL. Both `basic-kotlin-app` and `basic-spring-app` produce `.ws` output that matches every assertion in `FixtureBuildTest`.

If a fixture fails with a Maven dependency resolution error mentioning `wirespec-extractor-core`, the publish dep isn't taking effect. Verify Step 1; confirm `build/it-repo/community/flock/wirespec/extractor/wirespec-extractor-core/` exists after running `./gradlew :extractor-core:publishMavenPublicationToItLocalRepository`.

- [ ] **Step 3: Run the full test suite once more for completeness**

```bash
./gradlew clean test :integration-tests:test
```

Expected: BUILD SUCCESSFUL. Every unit test in `extractor-core` and `extractor-maven-plugin` passes; both IT fixtures pass.

- [ ] **Step 4: Verify the published plugin POM declares the core dep**

```bash
./gradlew :extractor-maven-plugin:publishMavenPublicationToItLocalRepository :extractor-core:publishMavenPublicationToItLocalRepository
find build/it-repo -name "wirespec-extractor-maven-plugin-*.pom" | head -1 | xargs cat
```

Expected: the printed POM contains a `<dependency>` element:

```xml
<dependency>
  <groupId>community.flock.wirespec.extractor</groupId>
  <artifactId>wirespec-extractor-core</artifactId>
  <version>0.0.0-SNAPSHOT</version>
  <scope>compile</scope>
</dependency>
```

(Scope may be `runtime` instead of `compile`; either is correct — Maven will resolve it for plugin execution.)

If this dep is missing, Gradle's `maven-publish` didn't model the `project(":extractor-core")` dependency in the POM. That's a Gradle pom-customisation gap to address before publishing to a real registry — but for IT runs against the local repo, Gradle's runtime classpath resolution covers it. Note this as a follow-up if it doesn't appear.

- [ ] **Step 5: Verify both fixtures emit byte-identical .ws output to before the refactor**

The strongest behavior-preservation check is that `FixtureBuildTest` passes — every assertion in there pins down concrete `.ws` content (endpoint declarations, type fields, nullability, JDK-type filtering). If Step 2 was green, this is satisfied.

For an extra-paranoid manual check:

```bash
./gradlew :integration-tests:test --info | grep -E '\.ws|wirespec output'
```

Expected: log lines confirming `.ws` files were written for both fixtures. The IT verifiers (`verifyBasicKotlinApp`, `verifyBasicSpringApp`) already check the content; no manual diff needed.

- [ ] **Step 6: Commit**

```bash
git add integration-tests/build.gradle.kts
git commit -m "build(it): depend on :extractor-core publication for IT runs

The Maven plugin's published POM now declares wirespec-extractor-core
as a runtime dependency. Integration-test fixture builds need the core
artifact present in the IT-local repo when they resolve the plugin."
```

- [ ] **Step 7: Final sanity check — branch state**

```bash
git status --short
git log --oneline -10
```

Expected: `git status --short` is empty. `git log` shows seven new commits in this order (newest first):

```
build(it): depend on :extractor-core publication for IT runs
refactor(maven): move Maven adapter classes to ...extractor.maven
refactor(mojo): make ExtractMojo a thin shim over WirespecExtractor
feat(core): add WirespecExtractor public API
refactor(emitter): return list of written .ws files
refactor: move Maven-agnostic source to extractor-core
build: scaffold extractor-core module
refactor: rename plugin/ to extractor-maven-plugin/
```

(That's eight commits including Task 1.)

---

## Post-implementation: optional follow-ups (out of scope, listed for awareness)

- **`@RestController` Gradle plugin or CLI consumer** of `extractor-core`. Now possible because the core is published independently.
- **README update** in a follow-up PR to mention `wirespec-extractor-core` exists.
- **CHANGELOG/release notes** if the project adopts one. The relevant line: "Internal split into `wirespec-extractor-core` + `wirespec-extractor-maven-plugin`; no behavior change for Maven users."
