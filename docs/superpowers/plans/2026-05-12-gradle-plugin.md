# Gradle Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Gradle plugin (`community.flock.wirespec.extractor`) with the same auto-wiring + zero-config behaviour as the existing Maven plugin, plus two TestKit-driven integration tests that prove it produces correct `.ws` output on Kotlin and Java/Spring fixture projects.

**Architecture:** A new `:extractor-gradle-plugin` module is a thin shim over `WirespecExtractor.extract(ExtractConfig)`. Applying the plugin (after a JVM source plugin is present) registers a single `extractWirespec` task that reads `sourceSets.main.output.classesDirs` + `runtimeClasspath` and writes `.ws` files; `assemble` is made to depend on it so `gradle build` always extracts. Integration tests live in a new sibling module `:integration-tests-gradle` and use `GradleRunner` against fixture projects under `src/it/`, with an isolated TestKit dir so they never touch `~/.gradle`. One small core API change supports Gradle's multi-classesDir output: `ExtractConfig.classesDirectory: File` becomes `ExtractConfig.classesDirectories: List<File>`.

**Tech Stack:** Kotlin 2.1.20, Gradle (>= 8.5), JDK 21, `java-gradle-plugin`, `com.gradle.plugin-publish` 1.3.0, JUnit Jupiter 5.11, Kotest assertions 5.9, `gradleTestKit()`.

**Spec:** [`docs/superpowers/specs/2026-05-12-gradle-plugin-design.md`](../specs/2026-05-12-gradle-plugin-design.md)

---

## File Structure

**Created:**
- `extractor-gradle-plugin/build.gradle.kts`
- `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorPlugin.kt`
- `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorExtension.kt`
- `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/ExtractWirespecTask.kt`
- `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/GradleExtractLog.kt`
- `extractor-gradle-plugin/src/test/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorPluginTest.kt`
- `integration-tests-gradle/build.gradle.kts`
- `integration-tests-gradle/src/it/basic-kotlin-app/settings.gradle.kts`
- `integration-tests-gradle/src/it/basic-kotlin-app/build.gradle.kts`
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt`
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/UserDto.kt`
- `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Role.kt`
- `integration-tests-gradle/src/it/basic-spring-app/settings.gradle.kts`
- `integration-tests-gradle/src/it/basic-spring-app/build.gradle.kts`
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/UserDto.java`
- `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Role.java`
- `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`

**Modified:**
- `settings.gradle.kts` — include the two new modules.
- `gradle/libs.versions.toml` — add `gradle-plugin-publish` plugin alias.
- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractConfig.kt` — rename `classesDirectory: File` → `classesDirectories: List<File>`.
- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilder.kt` — `collectUrls(outputDirectory: File)` → `collectUrls(outputDirectories: List<File>)`.
- `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt` — iterate over the list; update the existence check; tweak the error message to be tool-neutral.
- `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt` — use the new field name; relax error-message assertion.
- `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilderTest.kt` — use the new parameter name.
- `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/ExtractMojo.kt` — pass a single-element list.
- `README.md` — document the Gradle plugin usage.

---

## Task 1: Rename `classesDirectory` → `classesDirectories` in core

**Files:**
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractConfig.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilder.kt`
- Modify: `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt`
- Modify: `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilderTest.kt`
- Modify: `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/ExtractMojo.kt`
- Modify: `extractor-maven-plugin/src/test/kotlin/community/flock/wirespec/extractor/maven/ExtractMojoTest.kt` (only if assertion text relies on Maven-specific wording — see Step 6)

Mechanical rename: existing tests cover the behaviour, so the work is to update production code + call sites + test data, then keep the suite green.

- [ ] **Step 1: Update `ExtractConfig`**

Replace the file at `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/ExtractConfig.kt` with:

```kotlin
package community.flock.wirespec.extractor

import java.io.File

/**
 * Input to [WirespecExtractor.extract].
 *
 * @property classesDirectories One or more directories of compiled `.class` files
 *   to scan. Maven projects pass one (`target/classes`); Gradle JVM projects
 *   typically pass two (`build/classes/java/main`, `build/classes/kotlin/main`).
 * @property runtimeClasspath  Additional jars and directories needed so the
 *   class loader can resolve types referenced by scanned classes.
 * @property outputDirectory   Where `.ws` files will be written.
 * @property basePackage       Optional package prefix to restrict scanning;
 *   `null` or blank means scan every package.
 * @property log               Logger sink. Defaults to [ExtractLog.NoOp].
 */
data class ExtractConfig(
    val classesDirectories: List<File>,
    val runtimeClasspath: List<File>,
    val outputDirectory: File,
    val basePackage: String? = null,
    val log: ExtractLog = ExtractLog.NoOp,
)
```

- [ ] **Step 2: Update `ClasspathBuilder.collectUrls` to accept a list**

Replace `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilder.kt` with:

```kotlin
package community.flock.wirespec.extractor.classpath

import java.io.File
import java.net.URL
import java.net.URLClassLoader

object ClasspathBuilder {

    /** Build a child URLClassLoader over the given URLs. */
    fun fromUrls(urls: List<URL>, parent: ClassLoader): URLClassLoader =
        URLClassLoader(urls.toTypedArray(), parent)

    /**
     * Combine class output directories with the runtime classpath into the
     * URL list to feed a URLClassLoader. The output dirs come first (in order)
     * so the project's own classes win over duplicates pulled in transitively.
     */
    fun collectUrls(runtimeClasspathElements: List<String>, outputDirectories: List<File>): List<URL> {
        val outputs = outputDirectories.map { it.toURI().toURL() }
        val deps = runtimeClasspathElements.map { File(it).toURI().toURL() }
        return (outputs + deps).distinct()
    }
}
```

- [ ] **Step 3: Update `ClasspathBuilderTest` for the new signature**

In `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilderTest.kt`, replace the third test (`fromMavenInputs combines runtime classpath with output dir`) with:

```kotlin
    @Test
    fun `collectUrls combines runtime classpath with output dirs`() {
        val classesDir = File("/tmp/classes")
        val kotlinClassesDir = File("/tmp/kotlin-classes")
        val jar = File("/tmp/dep.jar")

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = listOf(jar.absolutePath),
            outputDirectories = listOf(classesDir, kotlinClassesDir),
        )

        urls shouldContain classesDir.toURI().toURL()
        urls shouldContain kotlinClassesDir.toURI().toURL()
        urls shouldContain jar.toURI().toURL()
    }
```

(The other two tests in the file don't reference `collectUrls` and stay unchanged.)

- [ ] **Step 4: Update `WirespecExtractor.extract` to iterate the list**

In `extractor-core/src/main/kotlin/community/flock/wirespec/extractor/WirespecExtractor.kt`, replace the top of `extract(config)` (lines 27–39) with:

```kotlin
    fun extract(config: ExtractConfig): ExtractResult {
        val classesDirs = config.classesDirectories
        val hasAnyClasses = classesDirs.any { it.exists() && !it.listFiles().isNullOrEmpty() }
        if (!hasAnyClasses) {
            val paths = classesDirs.joinToString(", ") { it.absolutePath }
            throw WirespecExtractorException(
                "No compiled classes in $paths. Did compilation run before extraction?"
            )
        }
        assertOutputWritable(config.outputDirectory)

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = config.runtimeClasspath.map { it.absolutePath },
            outputDirectories = classesDirs,
        )
```

Leave everything below the URL collection unchanged (the `ClasspathBuilder.fromUrls(...).use { loader -> ... }` block still scans by package via the classloader, which already covers all output dirs).

- [ ] **Step 5: Update `WirespecExtractorTest` to the new API + message**

In `extractor-core/src/test/kotlin/community/flock/wirespec/extractor/WirespecExtractorTest.kt`:

1. Replace every `classesDirectory = thisModuleClassesDir()` with `classesDirectories = listOf(thisModuleClassesDir())`.
2. Replace `classesDirectory = missing` with `classesDirectories = listOf(missing)`.
3. In the missing-classes test, replace the two `shouldContain` assertions with:

```kotlin
        ex.message!! shouldContain "No compiled classes in"
        ex.message!! shouldContain "Did compilation run before extraction?"
```

- [ ] **Step 6: Update `ExtractMojo` + its test for the new field**

In `extractor-maven-plugin/src/main/kotlin/community/flock/wirespec/extractor/maven/ExtractMojo.kt`, change the `ExtractConfig(...)` call (lines 42–50) to:

```kotlin
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectories = listOf(File(project.build.outputDirectory)),
                    runtimeClasspath = runtimeClasspath,
                    outputDirectory = output,
                    basePackage = basePackage,
                    log = MavenExtractLog(log),
                )
            )
```

`ExtractMojoTest` only asserts `ex.message shouldContain "No compiled classes in"` (which is still in the new error message), so it requires no change.

- [ ] **Step 7: Run the whole build; expect green**

Run:
```
./gradlew clean check
```
Expected: BUILD SUCCESSFUL. All tests in `:extractor-core` and `:extractor-maven-plugin` (and the existing Maven IT, if you have `mvn` on PATH) pass.

If any test compile fails, you missed a call site — search for `classesDirectory =` in the repo:
```
grep -rn "classesDirectory =" extractor-core extractor-maven-plugin
```
Each hit should now be `classesDirectories = listOf(...)`. Re-run `check`.

- [ ] **Step 8: Commit**

```
git add extractor-core extractor-maven-plugin
git commit -m "refactor(core): rename classesDirectory -> classesDirectories (List<File>)

Gradle JVM projects produce classes in multiple output directories
(build/classes/java/main, build/classes/kotlin/main). Generalising
the field to a list lets the Gradle plugin pass them all without
hacks; the Maven plugin passes a single-element list.

The 'Did compile run before extract?' message is now tool-neutral
('Did compilation run before extraction?')."
```

---

## Task 2: Add `:extractor-gradle-plugin` module skeleton

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `extractor-gradle-plugin/build.gradle.kts`

Module exists, compiles (empty source), and is wired into the Gradle build.

- [ ] **Step 1: Add the new plugin alias to `libs.versions.toml`**

In `gradle/libs.versions.toml`:

- Under `[versions]`, after the `maven-plugin-development = "1.0.3"` line, add:
```toml
gradle-plugin-publish = "1.3.0"
```
- Under `[plugins]`, after the existing `maven-plugin-development = { ... }` line, add:
```toml
gradle-plugin-publish = { id = "com.gradle.plugin-publish", version.ref = "gradle-plugin-publish" }
```

- [ ] **Step 2: Include the new module in `settings.gradle.kts`**

In `settings.gradle.kts`, replace the existing include block (lines 17–19) with:

```kotlin
include(":extractor-core")
include(":extractor-maven-plugin")
include(":extractor-gradle-plugin")
include(":integration-tests")
include(":integration-tests-gradle")
```

(Including `:integration-tests-gradle` now is fine — Task 5 creates its build file. Until then, Gradle prints a "Cannot find build file" warning when you sync from IntelliJ; running CLI tasks still works as long as you don't target that module. If you'd rather, defer this single include line to Task 5.)

- [ ] **Step 3: Create the module build script**

Create `extractor-gradle-plugin/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.gradle.plugin.publish)
    `maven-publish`
}

description = "Gradle plugin: extracts Spring Boot endpoints into Wirespec .ws files."
base.archivesName.set("wirespec-extractor-gradle-plugin")

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    website.set("https://wirespec.io")
    vcsUrl.set("https://github.com/flock-community/wirespec-extractor")
    plugins {
        register("wirespecExtractor") {
            id = "community.flock.wirespec.extractor"
            displayName = "Wirespec Extractor"
            description = project.description
            implementationClass = "community.flock.wirespec.extractor.gradle.WirespecExtractorPlugin"
            tags.set(listOf("wirespec", "spring", "openapi", "schema"))
        }
    }
}

dependencies {
    // Core extraction logic. Consumers resolve it transitively from the published POM.
    implementation(project(":extractor-core"))
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

// Configure the auto-generated `pluginMaven` publication (jar) + `<id>PluginMarkerMaven`
// (marker) created by java-gradle-plugin. We override the jar's artifactId so the
// Maven coordinate matches the descriptive `wirespec-extractor-gradle-plugin`
// rather than the module name.
publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "wirespec-extractor-gradle-plugin"
            pom {
                name.set("Wirespec Extractor Gradle Plugin")
                description.set(project.description)
            }
        }
    }
    repositories {
        // Build-local repo used by :integration-tests-gradle. Publishing here
        // keeps the fixture Gradle builds isolated from the user's ~/.m2.
        maven {
            name = "itLocal"
            url = uri(rootProject.layout.buildDirectory.dir("it-repo"))
        }
    }
}
```

- [ ] **Step 4: Create empty source root so module compiles**

Create an empty placeholder so Kotlin compile doesn't error on a missing source directory. From the repo root:

```
mkdir -p extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle
mkdir -p extractor-gradle-plugin/src/test/kotlin/community/flock/wirespec/extractor/gradle
```

(Gradle tolerates an empty `src/main/kotlin`; no `.gitkeep` needed once Task 3 lands real sources.)

- [ ] **Step 5: Verify the module configures**

Run:
```
./gradlew :extractor-gradle-plugin:tasks --no-daemon
```
Expected: Gradle prints the task list including `extractWirespec`-relevant lifecycle tasks (`build`, `check`, `publishToMavenLocal`, `publishAllPublicationsToItLocalRepository`, etc.). No errors.

If you see "Plugin id 'com.gradle.plugin-publish' not found", re-check Step 1's TOML edits.

- [ ] **Step 6: Commit**

```
git add settings.gradle.kts gradle/libs.versions.toml extractor-gradle-plugin/build.gradle.kts
git commit -m "build: add :extractor-gradle-plugin module skeleton

Wires up java-gradle-plugin + com.gradle.plugin-publish + maven-publish
with the descriptive artifactId. Plugin sources land in the next commit."
```

---

## Task 3: Implement extension, task, log adapter, and plugin (TDD)

**Files:**
- Create: `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorExtension.kt`
- Create: `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/GradleExtractLog.kt`
- Create: `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/ExtractWirespecTask.kt`
- Create: `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorPlugin.kt`
- Create: `extractor-gradle-plugin/src/test/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorPluginTest.kt`

Unit tests use `ProjectBuilder` (in-memory project model) to verify plugin apply, extension creation, task registration, and the `assemble.dependsOn(extractWirespec)` wiring without spinning up a real build. The `TaskAction` itself (and the `GradleExtractLog` adapter) is exercised by Task 8's integration tests — Gradle's `Logger` interface is too large to stub usefully for a 4-line adapter, and the IT's `forwardOutput()` already verifies log routing implicitly.

- [ ] **Step 1: Implement `GradleExtractLog`**

Create `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/GradleExtractLog.kt`:

```kotlin
package community.flock.wirespec.extractor.gradle

import community.flock.wirespec.extractor.ExtractLog
import org.gradle.api.logging.Logger

/**
 * Adapts Gradle's [Logger] to core's [ExtractLog] sink. Mirrors the Maven
 * adapter in :extractor-maven-plugin so both plugins surface the same
 * "Found N controller(s)" / "Wrote N .ws file(s)..." messages from core.
 *
 * No unit test: Gradle's Logger interface is too large to stub usefully for
 * a 4-line adapter. The :integration-tests-gradle IT exercises this code
 * path end-to-end (with TestKit's `forwardOutput()` surfacing any failure).
 */
internal class GradleExtractLog(private val logger: Logger) : ExtractLog {
    override fun info(msg: String) { logger.info(msg) }
    override fun warn(msg: String) { logger.warn(msg) }
}
```

- [ ] **Step 2: Write the failing plugin/extension test**

Create `extractor-gradle-plugin/src/test/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorPluginTest.kt`:

```kotlin
package community.flock.wirespec.extractor.gradle

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.io.File

class WirespecExtractorPluginTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply("community.flock.wirespec.extractor")
    }

    @Test
    fun `applying the plugin registers the wirespec extension`() {
        val project = project()

        val extension = project.extensions.findByName("wirespec")
        extension.shouldNotBeNull()
        (extension is WirespecExtractorExtension) shouldBe true
    }

    @Test
    fun `applying the plugin alone does not create the task (no java plugin)`() {
        val project = project()

        // Without the JavaPlugin, the task is not registered: nothing to extract from.
        project.tasks.findByName("extractWirespec") shouldBe null
    }

    @Test
    fun `applying java plugin then ours registers extractWirespec`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        project.plugins.apply("community.flock.wirespec.extractor")

        val task = project.tasks.findByName("extractWirespec")
        task.shouldNotBeNull()
        (task is ExtractWirespecTask) shouldBe true
    }

    @Test
    fun `applying ours then java plugin still registers extractWirespec`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("community.flock.wirespec.extractor")
        project.plugins.apply("java")

        project.tasks.findByName("extractWirespec").shouldNotBeNull()
    }

    @Test
    fun `extension outputDir defaults to build wirespec`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("community.flock.wirespec.extractor")

        val ext = project.extensions.getByType(WirespecExtractorExtension::class.java)

        val expected = File(project.layout.buildDirectory.get().asFile, "wirespec")
        ext.outputDir.get().asFile shouldBe expected
    }

    @Test
    fun `assemble dependsOn extractWirespec when java plugin is applied`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        project.plugins.apply("community.flock.wirespec.extractor")

        val assemble = project.tasks.getByName("assemble")
        val deps = assemble.taskDependencies.getDependencies(assemble).map { it.name }
        deps shouldContain "extractWirespec"
    }
}
```

- [ ] **Step 3: Run the failing test**

Run:
```
./gradlew :extractor-gradle-plugin:test --tests "*WirespecExtractorPluginTest*"
```
Expected: FAIL with unresolved references for `WirespecExtractorExtension`, `ExtractWirespecTask`, and the plugin ID resolution.

- [ ] **Step 4: Implement `WirespecExtractorExtension`**

Create `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorExtension.kt`:

```kotlin
package community.flock.wirespec.extractor.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Build-script DSL for the Gradle plugin:
 *
 * ```kotlin
 * wirespec {
 *     outputDir.set(layout.buildDirectory.dir("wirespec"))   // default
 *     basePackage.set("com.acme.api")
 * }
 * ```
 */
abstract class WirespecExtractorExtension {
    abstract val outputDir: DirectoryProperty
    abstract val basePackage: Property<String>
}
```

- [ ] **Step 5: Implement `ExtractWirespecTask`**

Create `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/ExtractWirespecTask.kt`:

```kotlin
package community.flock.wirespec.extractor.gradle

import community.flock.wirespec.extractor.ExtractConfig
import community.flock.wirespec.extractor.WirespecExtractor
import community.flock.wirespec.extractor.WirespecExtractorException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * Scans `classesDirs` for Spring controllers and emits `.ws` files into
 * `outputDirectory`. Mirrors :extractor-maven-plugin's `ExtractMojo`.
 *
 * Inputs/outputs are declared for Gradle's up-to-date checking and build cache.
 */
abstract class ExtractWirespecTask : DefaultTask() {

    @get:InputFiles @get:SkipWhenEmpty
    abstract val classesDirs: ConfigurableFileCollection

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input @get:Optional
    abstract val basePackage: Property<String>

    @TaskAction
    fun run() {
        try {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectories = classesDirs.files.toList(),
                    runtimeClasspath = runtimeClasspath.files.toList(),
                    outputDirectory = outputDirectory.asFile.get(),
                    basePackage = basePackage.orNull,
                    log = GradleExtractLog(logger),
                ),
            )
        } catch (e: WirespecExtractorException) {
            throw GradleException(e.message ?: "Wirespec extraction failed", e)
        }
    }
}
```

- [ ] **Step 6: Implement `WirespecExtractorPlugin`**

Create `extractor-gradle-plugin/src/main/kotlin/community/flock/wirespec/extractor/gradle/WirespecExtractorPlugin.kt`:

```kotlin
package community.flock.wirespec.extractor.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer

/**
 * Gradle entry point for the Wirespec extractor.
 *
 * Apply alongside any JVM source plugin (`java`, `kotlin("jvm")`, etc.):
 *
 * ```kotlin
 * plugins {
 *     kotlin("jvm")
 *     id("community.flock.wirespec.extractor")
 * }
 * wirespec { basePackage.set("com.acme.api") }
 * ```
 *
 * Wiring:
 *   - Registers `wirespec { outputDir; basePackage }` extension.
 *   - When [JavaPlugin] is applied (Kotlin-JVM applies it under the hood),
 *     registers the `extractWirespec` task using `sourceSets.main` outputs
 *     and runtime classpath, and makes `assemble` depend on it so
 *     `gradle build` always extracts.
 */
class WirespecExtractorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("wirespec", WirespecExtractorExtension::class.java).apply {
            outputDir.convention(project.layout.buildDirectory.dir("wirespec"))
            // basePackage left without a convention → null/unset means scan everything.
        }

        project.plugins.withType(JavaPlugin::class.java) {
            val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")

            val extractTask = project.tasks.register("extractWirespec", ExtractWirespecTask::class.java) { t ->
                t.group = "wirespec"
                t.description = "Extract Wirespec .ws files from Spring controllers."
                t.classesDirs.from(main.output.classesDirs)
                t.runtimeClasspath.from(main.runtimeClasspath)
                t.outputDirectory.convention(ext.outputDir)
                t.basePackage.convention(ext.basePackage)
                // Make sure compile runs first — classes producers wire the dependency.
                t.dependsOn(main.output.classesDirs)
            }

            // Auto-wire: every `gradle build`/`gradle assemble` runs the extractor.
            project.tasks.named("assemble") { it.dependsOn(extractTask) }
        }
    }
}
```

- [ ] **Step 7: Run the plugin unit tests — expect green**

Run:
```
./gradlew :extractor-gradle-plugin:test
```
Expected: PASS — all 6 tests in `WirespecExtractorPluginTest`.

- [ ] **Step 8: Commit**

```
git add extractor-gradle-plugin/src
git commit -m "feat(gradle): add Gradle plugin shim over WirespecExtractor

Apply with id 'community.flock.wirespec.extractor' alongside any
JVM source plugin. Registers an 'extractWirespec' task wired to
sourceSets.main outputs + runtime classpath, with outputDir defaulting
to build/wirespec and basePackage configurable via the 'wirespec'
extension. 'assemble' depends on the task so 'gradle build' always
extracts, matching the Maven plugin's process-classes auto-bind."
```

---

## Task 4: Verify the plugin publishes to the IT-local repo

**Files:** none (validation only).

The publishing config is already in Task 2's `build.gradle.kts`. This task confirms the marker + jar publications land in `build/it-repo/` with the right coordinates before the IT depends on it.

- [ ] **Step 1: Publish locally**

```
./gradlew :extractor-gradle-plugin:publishAllPublicationsToItLocalRepository --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Inspect the published artifacts**

```
find build/it-repo -type f -name "*.pom" -o -name "*.jar" -o -name "*.module" | sort
```
Expected output includes:
- `build/it-repo/community/flock/wirespec/extractor/community.flock.wirespec.extractor.gradle.plugin/0.0.0-SNAPSHOT/community.flock.wirespec.extractor.gradle.plugin-0.0.0-SNAPSHOT.pom` (the **plugin marker** — its POM declares a dependency on the actual plugin artifact)
- `build/it-repo/community/flock/wirespec/extractor/wirespec-extractor-gradle-plugin/0.0.0-SNAPSHOT/wirespec-extractor-gradle-plugin-0.0.0-SNAPSHOT.jar` (the **plugin implementation jar**)
- `build/it-repo/community/flock/wirespec/extractor/wirespec-extractor-gradle-plugin/0.0.0-SNAPSHOT/wirespec-extractor-gradle-plugin-0.0.0-SNAPSHOT.pom` (its POM)

If the artifactId is `extractor-gradle-plugin` instead of `wirespec-extractor-gradle-plugin`, the `publications.withType<MavenPublication>().configureEach { ... }` block in `build.gradle.kts` didn't apply. Re-verify Task 2 Step 3.

- [ ] **Step 3: No commit needed** — validation only.

---

## Task 5: Add `:integration-tests-gradle` module skeleton

**Files:**
- Create: `integration-tests-gradle/build.gradle.kts`

(`settings.gradle.kts` already includes the module — Task 2 Step 2.)

- [ ] **Step 1: Create the IT module build script**

Create `integration-tests-gradle/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "End-to-end tests that drive the Gradle plugin against fixture Gradle/Spring/Kotlin projects via TestKit."

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(gradleTestKit())
}

// Path used by the test runner to locate the IT-local Maven repo where the
// Gradle plugin + extractor-core have just been published. Shared with the
// :integration-tests (Maven) module — both write into the same `it-repo`.
val itRepoDir = rootProject.layout.buildDirectory.dir("it-repo")

tasks.test {
    useJUnitPlatform()

    // Publish both the plugin jar AND the Gradle plugin marker artifact, plus
    // extractor-core (transitive dependency), into the project-local repo
    // before the IT runs. Marker is what makes `plugins { id("...") }` resolve.
    dependsOn(":extractor-gradle-plugin:publishAllPublicationsToItLocalRepository")
    dependsOn(":extractor-core:publishMavenPublicationToItLocalRepository")

    systemProperty("it.pluginVersion", project.version.toString())
    systemProperty("it.repo", itRepoDir.get().asFile.absolutePath)
    systemProperty("it.fixturesRoot", layout.projectDirectory.dir("src/it").asFile.absolutePath)
    systemProperty("it.workRoot", layout.buildDirectory.dir("it-work").get().asFile.absolutePath)
    systemProperty("it.testKitDir", layout.buildDirectory.dir("it-testkit").get().asFile.absolutePath)

    // `gradle` is invoked via TestKit (in-process). Show its output only on failure.
    testLogging {
        showStandardStreams = false
        events("failed")
    }
}
```

- [ ] **Step 2: Verify the module configures**

```
./gradlew :integration-tests-gradle:tasks --no-daemon
```
Expected: prints task list including `test`. No errors.

- [ ] **Step 3: Commit**

```
git add integration-tests-gradle/build.gradle.kts
git commit -m "build: add :integration-tests-gradle module skeleton

Wires test depends-on publishing for both extractor-core and the
Gradle plugin (jar + marker) into the shared it-repo. Fixtures and
TestKit driver land in subsequent commits."
```

---

## Task 6: Add `basic-kotlin-app` fixture

**Files:**
- Create: `integration-tests-gradle/src/it/basic-kotlin-app/settings.gradle.kts`
- Create: `integration-tests-gradle/src/it/basic-kotlin-app/build.gradle.kts`
- Create: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt`
- Create: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/UserDto.kt`
- Create: `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Role.kt`

Sources mirror the Maven `basic-kotlin-app` fixture exactly so verification assertions transfer.

- [ ] **Step 1: Create the fixture `settings.gradle.kts`**

Create `integration-tests-gradle/src/it/basic-kotlin-app/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        // Substituted by the test runner with the it-repo URL where the
        // freshly-built Gradle plugin (jar + marker) lives.
        maven { url = uri("@itRepo@") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Same it-repo for extractor-core (transitive of the plugin),
        // then Maven Central for everything else (Spring, Kotlin stdlib, ...).
        maven { url = uri("@itRepo@") }
        mavenCentral()
    }
}

rootProject.name = "basic-kotlin-app"
```

- [ ] **Step 2: Create the fixture `build.gradle.kts`**

Create `integration-tests-gradle/src/it/basic-kotlin-app/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    // Substituted by the test runner with the plugin version under test.
    id("community.flock.wirespec.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // So Spring's @PathVariable/@RequestParam (and our extractor) can recover
        // parameter names that have no explicit value().
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespec {
    basePackage.set("com.acme.api")
}
```

- [ ] **Step 3: Create the controller**

Create `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/UserController.kt`:

```kotlin
package com.acme.api

import com.acme.api.dto.UserDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController {

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: String): UserDto = throw NotImplementedError()

    @PostMapping
    fun createUser(@RequestBody body: UserDto): UserDto = body

    // Coroutines: a Kotlin suspend function compiles to a Java method with a
    // trailing `Continuation<? super T>` parameter and an erased `Object` return.
    // The extractor must recover T (here: List<UserDto>) and not leak Continuation
    // / CoroutineContext into the schema.
    @GetMapping
    suspend fun listUsers(): List<UserDto> = throw NotImplementedError()

    @DeleteMapping("/{id}")
    suspend fun deleteUser(@PathVariable id: String) { /* Unit-returning suspend → 204 */ }
}
```

- [ ] **Step 4: Create the DTOs**

Create `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/UserDto.kt`:

```kotlin
package com.acme.api.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

data class UserDto(
    val id: String,
    val age: Int,
    val active: Boolean,
    val role: Role,
    val tags: List<String>,
    val nickname: String?,
    val createdAt: LocalDateTime,
    val lastSeen: Instant,
    val timezone: ZoneOffset,
    val balance: BigDecimal,
    val _internalId: String,
    val SystemKey: String,
)
```

Create `integration-tests-gradle/src/it/basic-kotlin-app/src/main/kotlin/com/acme/api/dto/Role.kt`:

```kotlin
package com.acme.api.dto

enum class Role { ADMIN, MEMBER }
```

- [ ] **Step 5: Commit**

```
git add integration-tests-gradle/src/it/basic-kotlin-app
git commit -m "test(gradle-it): add basic-kotlin-app fixture

Mirrors integration-tests/src/it/basic-kotlin-app: same Spring
controller + DTOs, Kotlin-DSL settings.gradle.kts + build.gradle.kts.
Test runner (next commit) substitutes @itRepo@ and @project.version@."
```

---

## Task 7: Add `basic-spring-app` fixture (Java)

**Files:**
- Create: `integration-tests-gradle/src/it/basic-spring-app/settings.gradle.kts`
- Create: `integration-tests-gradle/src/it/basic-spring-app/build.gradle.kts`
- Create: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`
- Create: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/UserDto.java`
- Create: `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Role.java`

- [ ] **Step 1: Create the fixture `settings.gradle.kts`**

Create `integration-tests-gradle/src/it/basic-spring-app/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("@itRepo@") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("@itRepo@") }
        mavenCentral()
    }
}

rootProject.name = "basic-spring-app"
```

- [ ] **Step 2: Create the fixture `build.gradle.kts`**

Create `integration-tests-gradle/src/it/basic-spring-app/build.gradle.kts`:

```kotlin
plugins {
    java
    id("community.flock.wirespec.extractor") version "@project.version@"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

// So Spring's @PathVariable/@RequestParam (and our extractor) can recover
// parameter names that have no explicit value().
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

wirespec {
    basePackage.set("com.acme.api")
}
```

- [ ] **Step 3: Create the controller**

Create `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`:

```java
package com.acme.api;

import com.acme.api.dto.UserDto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
    @GetMapping("/{id}")
    public UserDto getUser(@PathVariable String id) { return null; }

    @PostMapping
    public UserDto createUser(@RequestBody UserDto body) { return body; }
}
```

- [ ] **Step 4: Create the DTOs**

Create `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/UserDto.java`:

```java
package com.acme.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
public record UserDto(
    String id,
    int age,
    boolean active,
    Role role,
    List<String> tags,
    LocalDateTime createdAt,
    Instant lastSeen,
    ZoneOffset timezone,
    BigDecimal balance,
    String _internalId,
    String SystemKey
) {}
```

Create `integration-tests-gradle/src/it/basic-spring-app/src/main/java/com/acme/api/dto/Role.java`:

```java
package com.acme.api.dto;
public enum Role { ADMIN, MEMBER }
```

- [ ] **Step 5: Commit**

```
git add integration-tests-gradle/src/it/basic-spring-app
git commit -m "test(gradle-it): add basic-spring-app fixture (Java)

Mirrors integration-tests/src/it/basic-spring-app: same Spring
controller + record DTOs, plain Java Gradle build (-parameters
javac flag enabled)."
```

---

## Task 8: Implement `GradleFixtureBuildTest` and run end-to-end

**Files:**
- Create: `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`

This is the TestKit driver — equivalent of `:integration-tests`'s `FixtureBuildTest.kt`. One `@TestFactory` emits two dynamic tests (`basic-kotlin-app`, `basic-spring-app`). The verifier methods are Kotlin ports of the Maven IT's verifiers, with `target/wirespec` → `build/wirespec`.

- [ ] **Step 1: Create the test class**

Create `integration-tests-gradle/src/test/kotlin/community/flock/wirespec/extractor/it/gradle/GradleFixtureBuildTest.kt`:

```kotlin
package community.flock.wirespec.extractor.it.gradle

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
            .withArguments("assemble", "--stacktrace", "--no-daemon")
            .forwardOutput()
            .build()

        assertTrue(result.output.isNotEmpty()) { "Gradle produced no output for ${fixture.name}" }

        when (fixture.name) {
            "basic-kotlin-app" -> verifyBasicKotlinApp(workDir)
            "basic-spring-app" -> verifyBasicSpringApp(workDir)
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
        files.shouldContainExactly("UserController.ws", "types.ws")

        val controller = File(wsDir, "UserController.ws").readText()
        controller shouldContain "endpoint GetUser GET /users/{id"
        controller shouldContain "endpoint CreateUser POST"

        // Suspend endpoints: response type recovered from the Continuation type
        // argument, not emitted as `Object`/`String`/missing.
        controller shouldMatch Regex("(?s).*endpoint ListUsers GET /users\\b.*")
        controller shouldMatch Regex("(?s).*200 -> UserDto\\[].*")
        controller shouldMatch Regex("(?s).*endpoint DeleteUser DELETE /users/\\{id.*")
        controller shouldMatch Regex("(?s).*204 -> Unit.*")

        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type UserDto"
        types shouldContain "Role"

        // Kotlin nullability path: data class non-null properties stay non-null,
        // explicitly `String?` becomes nullable. (Java records come out all-nullable.)
        types shouldMatch Regex("(?s).*id\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*nickname\\s*:\\s*String\\?.*")

        // JDK value types must be filtered to `String` rather than expanded as
        // nested Wirespec types.
        types shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\b(?!\\?).*")
        types shouldMatch Regex("(?s).*balance\\s*:\\s*String\\b(?!\\?).*")

        // JDK class names must not leak as Wirespec definitions.
        listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(types)) {
                "JDK type $jdk leaked into types.ws:\n$types"
            }
        }

        // Kotlin coroutine machinery must never appear as Wirespec types.
        listOf("Continuation", "CoroutineContext").forEach { ktInternal ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$ktInternal\\b").containsMatchIn(types)) {
                "Kotlin coroutine type $ktInternal leaked into types.ws:\n$types"
            }
        }

        // Field names starting with `_` or an uppercase letter must be backticked.
        types shouldContain "`_internalId`"
        types shouldContain "`SystemKey`"
        assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(types)) {
            "_internalId appears un-backticked at line start:\n$types"
        }
    }

    private fun verifyBasicSpringApp(workDir: File) {
        val wsDir = File(workDir, "build/wirespec")
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        val files = wsDir.listFiles()!!.map { it.name }.sorted()
        files.shouldContainExactly("UserController.ws", "types.ws")

        val controller = File(wsDir, "UserController.ws").readText()
        controller shouldContain "endpoint GetUser GET /users/{id"
        controller shouldContain "endpoint CreateUser POST"

        val types = File(wsDir, "types.ws").readText()
        types shouldContain "type UserDto"
        types shouldContain "Role"

        // JDK value types must be filtered to `String` — never emitted as nested
        // Wirespec types. (Java records produce all-nullable fields, so the
        // expected form is `: String?`.)
        types shouldMatch Regex("(?s).*createdAt\\s*:\\s*String\\??.*")
        types shouldMatch Regex("(?s).*lastSeen\\s*:\\s*String\\??.*")
        types shouldMatch Regex("(?s).*timezone\\s*:\\s*String\\??.*")
        types shouldMatch Regex("(?s).*balance\\s*:\\s*String\\??.*")

        // JDK class names must not leak as Wirespec definitions.
        listOf("LocalDateTime", "Instant", "ZoneOffset", "BigDecimal", "LocalDate", "ZonedDateTime").forEach { jdk ->
            assertTrue(!Regex("(?m)^\\s*(type|enum|refined)\\s+$jdk\\b").containsMatchIn(types)) {
                "JDK type $jdk leaked into types.ws:\n$types"
            }
        }

        // Field names starting with `_` or an uppercase letter must be backticked.
        types shouldContain "`_internalId`"
        types shouldContain "`SystemKey`"
        assertTrue(!Regex("(?m)^\\s*_internalId\\s*:").containsMatchIn(types)) {
            "_internalId appears un-backticked at line start:\n$types"
        }
    }

    private fun sysProp(key: String): String =
        System.getProperty(key) ?: error("System property '$key' not set")
}
```

- [ ] **Step 2: Run the integration tests**

```
./gradlew :integration-tests-gradle:test --no-daemon
```
Expected: BUILD SUCCESSFUL, two dynamic tests pass (`basic-kotlin-app`, `basic-spring-app`). First run downloads the Gradle distribution into `integration-tests-gradle/build/it-testkit/` — give it a few minutes.

- [ ] **Step 3: Verify the .ws files exist on disk**

```
ls integration-tests-gradle/build/it-work/basic-kotlin-app/build/wirespec
ls integration-tests-gradle/build/it-work/basic-spring-app/build/wirespec
```
Expected for each: `UserController.ws` and `types.ws`.

- [ ] **Step 4: Diagnose if `basic-kotlin-app` fails**

Most likely failure modes and fixes:

- **`Plugin [id: '…'] was not found in any of the following sources`**: the marker artifact didn't make it to `it-repo`. Re-run Task 4 Step 2's inspection; check that the IT module's `tasks.test.dependsOn(":extractor-gradle-plugin:publishAllPublicationsToItLocalRepository")` line is present.
- **`Could not resolve community.flock.wirespec.extractor:wirespec-extractor-gradle-plugin`**: the `dependencyResolutionManagement.repositories` block in the fixture's `settings.gradle.kts` is missing `@itRepo@` substitution. Verify the file is one of the two `substituteTokens` targets (it is — both `settings.gradle.kts` and `build.gradle.kts` are walked).
- **`Could not find or load main class WirespecExtractor` / `ClassNotFoundException: io.github.classgraph...`**: `extractor-core` wasn't published to `it-repo`. Confirm the IT's `tasks.test.dependsOn(":extractor-core:publishMavenPublicationToItLocalRepository")` line.
- **Verification regex failure (e.g. `endpoint ListUsers …`)**: actual output differs from Maven IT — diff `integration-tests-gradle/build/it-work/basic-kotlin-app/build/wirespec/` vs `integration-tests/build/it-work/basic-kotlin-app/target/wirespec/` (after a Maven IT run) to spot the regression.

- [ ] **Step 5: Commit**

```
git add integration-tests-gradle/src/test
git commit -m "test(gradle-it): TestKit-driven fixture build + .ws verification

Two dynamic tests, one per fixture: copy fixture to sandbox,
substitute @project.version@ and @itRepo@, run 'gradle assemble'
via TestKit against an isolated testKitDir, then verify
controller/types content matches the Maven IT expectations."
```

---

## Task 9: Document the Gradle plugin in `README.md`

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a Gradle usage section**

In `README.md`, between the existing `## Usage` (Maven) section and the `## What it extracts` section, insert a new `## Usage (Gradle)` block:

```markdown
## Usage (Gradle)

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"               // or `java`
    id("community.flock.wirespec.extractor") version "0.1.0"
}

wirespec {
    // optional — defaults to build/wirespec
    // outputDir.set(layout.buildDirectory.dir("wirespec"))

    // optional — only scan classes under this package
    basePackage.set("com.acme.api")
}
```

Applying the plugin alongside any JVM source plugin auto-wires
`extractWirespec` into `assemble`, so `gradle build` (or `gradle assemble`)
produces `.ws` files in `build/wirespec/`. Run `gradle extractWirespec` to
trigger it directly.
```

- [ ] **Step 2: Update the project title/intro line if needed**

The README's `# wirespec-extractor-maven-plugin` header is now narrower than what the repo ships. Change line 1 to:

```markdown
# wirespec-extractor
```

And in the intro paragraph (line 3), change:

```
A Maven plugin that scans a Spring Boot application's compiled classes…
```

to:

```
A Maven and Gradle plugin that scans a Spring Boot application's compiled classes…
```

- [ ] **Step 3: Final check — run the whole build**

```
./gradlew clean check --no-daemon
```
Expected: BUILD SUCCESSFUL. All unit tests + both integration test modules pass.

- [ ] **Step 4: Commit**

```
git add README.md
git commit -m "docs: document Gradle plugin usage in README

Adds a 'Usage (Gradle)' section alongside the existing Maven one,
and broadens the project header / intro from 'Maven plugin' to
'Maven and Gradle plugin'."
```

---

## Done criteria

When all tasks are complete:

1. `./gradlew clean check --no-daemon` runs to completion with no failures.
2. The new `:integration-tests-gradle:test` task emits two passing dynamic tests (`basic-kotlin-app`, `basic-spring-app`).
3. `find build/it-repo -name '*.pom'` shows both the plugin jar POM and the plugin marker POM under `community/flock/wirespec/extractor/`.
4. The README documents both Maven and Gradle usage.
5. Six commits on the branch (one per task that produces a commit: Tasks 1, 2, 3, 5, 6, 7, 8, 9 — eight commits total; Task 4 is validation-only).
