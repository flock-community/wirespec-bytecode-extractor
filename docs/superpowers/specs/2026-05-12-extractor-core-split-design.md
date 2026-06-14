# Split: extractor-core + extractor-maven-plugin

**Date:** 2026-05-12
**Status:** Approved (design phase)

## Background

The current `plugin/` module is a single Gradle subproject that contains both:

- **Maven-coupled code** — `ExtractMojo`, `WirespecLifecycleParticipant`, `META-INF/plexus/components.xml`, generated `HelpMojo`.
- **Maven-agnostic extraction logic** — controller scanning, type/endpoint extraction, Wirespec AST building, `.ws` emission, classpath building. None of this code imports `org.apache.maven.*`.

The mojo's `execute()` body is ~50 lines of orchestration that operates almost entirely on Maven-agnostic types. The only Maven dependencies are:

1. Reading `project.runtimeClasspathElements` and `project.build.outputDirectory`.
2. Throwing `MojoExecutionException` on validation failures.
3. Logging through `org.apache.maven.plugin.logging.Log`.

This means the same logic could drive a Gradle plugin, a CLI, or programmatic use — but today it can't, because consumers would need to drag in the Maven plugin API to depend on the existing module.

## Goals

1. **Make the extractor logic reusable** outside Maven by publishing it as a standalone Kotlin library (`wirespec-bytecode-extractor-core`).
2. **Keep the Maven plugin a thin shim** (~30 lines of actual logic in `ExtractMojo`) that adapts Maven types to core types and Maven `Log` to a core `ExtractLog`.
3. **Preserve the public Maven plugin artifact** — same groupId, same artifactId (`wirespec-bytecode-extractor-maven-plugin`), same goal prefix (`wirespec`), same default phase binding (`process-classes`), same `<extensions>true</extensions>` auto-bind behavior. **No breaking change for existing pom.xml consumers.**
4. **Preserve all existing test coverage** by moving tests with their subject code; add a new public-API contract test for `WirespecExtractor.extract(ExtractConfig)`.

## Non-goals

- No behavior changes to extraction, scanning, emission, or auto-binding. This is purely a structural split.
- No new features (no Gradle plugin, no CLI). Those become _possible_ but are out of scope for this change.
- No `@PublishedApi`/`@InternalApi` opt-in machinery — core's public API is committed-public from day one (one entry point object + three data classes + one interface + one exception).
- No change to the integration test contract; fixtures and verifiers stay byte-identical.

## Architecture

### Module layout

```
wirespec-bytecode-extractor/
├── settings.gradle.kts                  [edited]
├── build.gradle.kts                     [unchanged]
├── extractor-core/                      [NEW]
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/community/flock/wirespec/bytecode/extractor/
│       │   ├── WirespecExtractor.kt           [NEW — public entry point]
│       │   ├── ExtractConfig.kt               [NEW]
│       │   ├── ExtractResult.kt               [NEW]
│       │   ├── ExtractLog.kt                  [NEW]
│       │   ├── WirespecExtractorException.kt  [NEW]
│       │   ├── classpath/ClasspathBuilder.kt          [moved verbatim]
│       │   ├── scan/ControllerScanner.kt              [moved verbatim]
│       │   ├── model/Endpoint.kt                      [moved verbatim]
│       │   ├── model/Param.kt                         [moved verbatim]
│       │   ├── model/WireType.kt                      [moved verbatim]
│       │   ├── extract/EndpointExtractor.kt           [moved verbatim]
│       │   ├── extract/TypeExtractor.kt               [moved verbatim]
│       │   ├── extract/ParamExtractor.kt              [moved verbatim]
│       │   ├── extract/NullabilityResolver.kt         [moved verbatim]
│       │   ├── extract/ReturnTypeUnwrapper.kt         [moved verbatim]
│       │   ├── extract/JacksonNames.kt                [moved verbatim]
│       │   ├── extract/ValidationConstraints.kt       [moved verbatim]
│       │   ├── ast/WirespecAstBuilder.kt              [moved verbatim]
│       │   └── emit/Emitter.kt                        [moved verbatim]
│       └── test/kotlin/community/flock/wirespec/bytecode/extractor/
│           ├── WirespecExtractorTest.kt               [NEW — public API contract]
│           ├── (all current unit tests except ExtractMojoTest, WirespecLifecycleParticipantTest)
│           └── fixtures/  (all current fixtures, unchanged)
│
├── extractor-maven-plugin/              [RENAMED from plugin/]
│   ├── build.gradle.kts                 [edited — fewer deps, project dep on :extractor-core]
│   └── src/
│       ├── main/kotlin/community/flock/wirespec/bytecode/extractor/maven/
│       │   ├── ExtractMojo.kt                  [rewritten as shim]
│       │   ├── WirespecLifecycleParticipant.kt [moved + repackaged, behavior unchanged]
│       │   └── MavenExtractLog.kt              [NEW — ExtractLog adapter over Maven Log]
│       ├── main/resources/META-INF/plexus/components.xml  [edited — new FQN]
│       └── test/kotlin/community/flock/wirespec/bytecode/extractor/maven/
│           ├── ExtractMojoTest.kt              [moved + repackaged]
│           └── WirespecLifecycleParticipantTest.kt [moved + repackaged]
│
├── integration-tests/                   [build.gradle.kts edited to point at new module name]
└── plugin/                              [DELETED]
```

### `extractor-core` public API

```kotlin
package community.flock.wirespec.bytecode.extractor

import java.io.File

object WirespecExtractor {
    /**
     * Scans [ExtractConfig.classesDirectory] for Spring controllers and writes
     * `<Controller>.ws` + (optional) `types.ws` files into
     * [ExtractConfig.outputDirectory].
     *
     * @throws WirespecExtractorException if validation fails, the configured
     *   classes directory is missing/empty, output is not writable, or two
     *   controllers share a simple name.
     */
    fun extract(config: ExtractConfig): ExtractResult
}

data class ExtractConfig(
    /** Directory of compiled `.class` files to scan (typically `target/classes`). */
    val classesDirectory: File,
    /** Additional jars and directories needed to resolve types referenced by scanned classes. */
    val runtimeClasspath: List<File>,
    /** Where `.ws` files will be written. */
    val outputDirectory: File,
    /** Optional package prefix to restrict scanning. `null` or blank means scan everything. */
    val basePackage: String? = null,
    /** Logger sink. Defaults to no-op. */
    val log: ExtractLog = ExtractLog.NoOp,
)

data class ExtractResult(
    val controllerCount: Int,
    val sharedTypeCount: Int,
    val filesWritten: List<File>,
)

interface ExtractLog {
    fun info(msg: String)
    fun warn(msg: String)

    object NoOp : ExtractLog {
        override fun info(msg: String) {}
        override fun warn(msg: String) {}
    }
}

class WirespecExtractorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

`WirespecExtractor.extract()` reproduces the current `ExtractMojo.execute()` body verbatim except:

1. `if (!classesDir.exists() || classesDir.listFiles().isNullOrEmpty())` → throws `WirespecExtractorException` (was `MojoExecutionException`). Message unchanged.
2. `assertOutputWritable(output)` → throws `WirespecExtractorException` on failure. Becomes an `internal` top-level function in `WirespecExtractor.kt`.
3. `detectControllerCollisions(controllers)` non-empty → throws `WirespecExtractorException`. Becomes an `internal` top-level function in `WirespecExtractor.kt`.
4. `effectiveBasePackage(raw)` → also moves to `WirespecExtractor.kt` as `internal`.
5. `log.info(...)` / `log.warn(...)` → `config.log.info(...)` / `config.log.warn(...)`.
6. Returns `ExtractResult(controllerCount = byController.size, sharedTypeCount = sharedTypes.size, filesWritten = emitter.write(...))` — requires `Emitter.write()` to return `List<File>` instead of `Unit`. (See "Emitter return type" below.)

#### Emitter return type

Today, `Emitter().write(...)` returns `Unit`. To populate `ExtractResult.filesWritten`, change its signature to return `List<File>` — the list of files actually written this run. This is a minor internal change with one call site (the mojo). All existing `EmitterTest` assertions on filesystem state stay valid; the new return value just gives the new test surface something to inspect.

### `extractor-maven-plugin` shim

```kotlin
package community.flock.wirespec.bytecode.extractor.maven

import community.flock.wirespec.bytecode.extractor.ExtractConfig
import community.flock.wirespec.bytecode.extractor.ExtractLog
import community.flock.wirespec.bytecode.extractor.WirespecExtractor
import community.flock.wirespec.bytecode.extractor.WirespecExtractorException
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
        try {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectory = File(project.build.outputDirectory),
                    runtimeClasspath = project.runtimeClasspathElements.map(::File),
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

internal class MavenExtractLog(
    private val mavenLog: org.apache.maven.plugin.logging.Log,
) : ExtractLog {
    override fun info(msg: String) { mavenLog.info(msg) }
    override fun warn(msg: String) { mavenLog.warn(msg) }
}
```

`WirespecLifecycleParticipant` keeps the same Plexus `hint = "wirespec-bytecode-extractor"` and the same `DEFAULT_PHASE = "process-classes"`; only its package changes to `community.flock.wirespec.bytecode.extractor.maven`. `META-INF/plexus/components.xml` updates `<implementation>` to the new FQN.

## Build configuration changes

### `settings.gradle.kts`

```kotlin
include(":extractor-core", ":extractor-maven-plugin", ":integration-tests")
```

(replacing the current `:plugin` include)

### `extractor-core/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

description = "Spring → Wirespec extraction logic. Maven-agnostic; drives the Maven plugin."
base.archivesName.set("wirespec-bytecode-extractor-core")

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

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "wirespec-bytecode-extractor-core"
            pom {
                name.set("Wirespec Bytecode Extractor Core")
                description.set(project.description)
            }
        }
    }
    repositories {
        maven {
            name = "itLocal"
            url = uri(rootProject.layout.buildDirectory.dir("it-repo"))
        }
    }
}
```

No `maven-plugin-development`, no `compileOnly(libs.maven.*)`, no plexus/buildscript ASM force, no `requiredJavaVersion`/`requiredMavenVersion` patching, no `META-INF/maven/...` jar embedding (that's plugin-specific).

### `extractor-maven-plugin/build.gradle.kts`

Identical to the current `plugin/build.gradle.kts` with these edits:

- `description` and any inline comments reference `extractor-maven-plugin` rather than `plugin/`.
- `dependencies { ... }`:
  - Keep: `compileOnly(libs.maven.plugin.api)`, `compileOnly(libs.maven.core)`, `compileOnly(libs.maven.plugin.annotations)`, `implementation(libs.kotlin.stdlib)`, `testImplementation(libs.maven.core)`, `testImplementation(libs.maven.plugin.api)`, `testImplementation(libs.junit.jupiter)`, `testImplementation(libs.kotest.assertions)`.
  - Remove: `implementation(libs.kotlin.reflect)`, `implementation(libs.classgraph)`, `implementation(libs.wirespec.core)`, `implementation(libs.wirespec.emitter)`, `implementation(libs.spring.web)`, `implementation(libs.spring.context)`, `implementation(libs.jackson.annotations)`, `implementation(libs.jakarta.validation)`, `implementation(libs.swagger.annotations)`, `testImplementation(libs.reactor.core)` — these moved with the code they support.
  - Add: `implementation(project(":extractor-core"))`.
- The `mavenPlugin { artifactId.set("wirespec-bytecode-extractor-maven-plugin") ... }` block is unchanged.
- The buildscript `force(...)` block for ASM 9.7.1 stays (it's about Maven plugin descriptor generation, which still runs here).
- `tasks.named<GenerateMavenPluginDescriptorTask>("generateMavenPluginDescriptor")` configuration stays (classesDirs patch, requiredJava/Maven patch).
- The `META-INF/maven/<groupId>/wirespec-bytecode-extractor-maven-plugin/` jar-embedding block stays.

### `integration-tests/build.gradle.kts`

Two edits:

```kotlin
dependsOn(":extractor-maven-plugin:publishMavenPublicationToItLocalRepository")
dependsOn(":extractor-core:publishMavenPublicationToItLocalRepository")
```

The second is required because the Maven plugin's POM now declares `wirespec-bytecode-extractor-core` as a runtime dependency, and Maven will resolve it from the IT-local repo during fixture builds.

## Behavior preserved (verification list)

These contracts are unchanged after the split. The implementation plan must verify each:

| Contract | Where it's tested |
|---|---|
| `mvn wirespec:extract` runs with default config | `integration-tests` fixture builds |
| `<extensions>true</extensions>` auto-binds extract to `process-classes` | `WirespecLifecycleParticipantTest` + `integration-tests` invoker.properties `clean package` runs |
| Plugin throws `MojoExecutionException` (not core's exception) when classes dir is missing | `ExtractMojoTest` (existing) |
| Controller collisions throw with the same message format | `ExtractMojoTest` (existing) |
| `.ws` file contents are byte-identical | `FixtureBuildTest` verifiers (existing) |
| Generated `HelpMojo` / `wirespec:help` still works | `mvn wirespec:help` smoke check in plan |
| Plugin artifact coords unchanged (`community.flock.wirespec.bytecode:wirespec-bytecode-extractor-maven-plugin`) | Manual + integration-tests pom |
| `requiredJavaVersion=21`, `requiredMavenVersion=3.9.0` in plugin.xml | `GenerateMavenPluginDescriptorTask` doLast (preserved) |

## Test strategy

### Moved with code (no behavior change)

- All extraction/scan/emit/AST/extract/classpath unit tests → `extractor-core`.
- All fixtures (controllers, DTOs, wrapped types, dto/clashA, dto/clashB, etc.) → `extractor-core`.
- `ExtractMojoTest`, `WirespecLifecycleParticipantTest` → `extractor-maven-plugin`.

### New tests in `extractor-core`

- **`WirespecExtractorTest`** — covers the public API:
  - `extract` with a valid `ExtractConfig` against the existing controller fixtures produces the expected `ExtractResult` (controllerCount, sharedTypeCount, filesWritten).
  - `extract` with missing/empty `classesDirectory` throws `WirespecExtractorException` with the existing message.
  - `extract` with a non-writable `outputDirectory` ancestor throws `WirespecExtractorException`.
  - `extract` with two same-simple-name controllers throws `WirespecExtractorException`.
  - `ExtractLog.NoOp` is the default and is safe.
  - A custom `ExtractLog` receives the same info/warn calls the mojo used to issue.

This replaces direct testing of `Mojo.execute()` behavior in `ExtractMojoTest` for the core paths. `ExtractMojoTest` is reduced (or kept and trimmed) to assert only the adapter layer: that `WirespecExtractorException` from core is rethrown as `MojoExecutionException`, that Maven `Log` calls are routed through `MavenExtractLog`, and that `project.runtimeClasspathElements`/`project.build.outputDirectory` are correctly mapped onto `ExtractConfig`.

### `ExtractMojoTest` trim plan

The existing `ExtractMojoTest` has tests that exercise core logic via the mojo (collision detection, missing classes dir, etc.). After the split, those scenarios are owned by `WirespecExtractorTest`. The mojo test keeps:

- "passing a fake `MavenProject` with empty `build.outputDirectory` causes `MojoExecutionException` (because core threw `WirespecExtractorException`, and the shim translated it)" — the smallest end-to-end adapter test.
- Removed: tests that scan real controllers via the mojo (those now live in core's `WirespecExtractorTest`).

The exact subset stays a decision for the implementation plan once the file is in front of us.

### Integration tests

`FixtureBuildTest` is unchanged; the only edit anywhere in the IT module is the two `dependsOn(...)` lines in `integration-tests/build.gradle.kts`. The IT runs verify byte-for-byte identical `.ws` output, which is the strongest preservation signal we have.

## Migration / compatibility notes

- **Maven consumers:** no change. Same groupId, artifactId, goal prefix, goal name, default phase, lifecycle-extension behavior. The plugin's published POM gains a `<dependency>` on `wirespec-bytecode-extractor-core` (resolved automatically by Maven from the same repo as the plugin).
- **In-repo Gradle:** the `plugin` subproject is deleted. Anyone who has cached IDE references to `:plugin` will need to re-import.
- **Future consumers of core:** can depend on `wirespec-bytecode-extractor-core` directly without dragging in the Maven plugin API. (Not exercised in this change; the Gradle/CLI/etc. consumers are out of scope.)

## Risks and open questions

- **`Emitter.write()` signature change.** Returning `List<File>` is a minor public-API change inside the now-public core module. We've decided this is acceptable since `Emitter` is being newly published and there's no prior committed contract for it. The implementation plan will verify all `EmitterTest` callsites compile after the change.
- **Plexus components.xml + new package.** The `<implementation>` FQN must be updated atomically with the package move; otherwise the lifecycle participant won't be discovered and `<extensions>true</extensions>` silently stops auto-binding. The implementation plan will verify this via the integration test (which has zero `<executions>`).
- **`maven-plugin-development` Gradle plugin and the package move.** The descriptor generator scans `sourceSets.main.get().kotlin.classesDirectory`; moving the mojo to `…extractor.maven` doesn't affect what's scanned (the whole Kotlin output dir is still the scan root), but the generated `HelpMojo.java` is emitted into a package controlled by the `helpMojoPackage` Gradle setting (currently `community.flock.wirespec.bytecode.wirespec_bytecode_extractor_maven_plugin`). That package doesn't need to match the mojo's package, so no change there.
- **No CHANGELOG/release-notes file exists.** When this is released, the relevant change message is "internal split into `wirespec-bytecode-extractor-core` + `wirespec-bytecode-extractor-maven-plugin`; no behavior change for Maven users."
