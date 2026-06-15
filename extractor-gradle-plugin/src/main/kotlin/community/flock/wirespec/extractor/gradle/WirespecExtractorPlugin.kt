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
 * wirespecExtractor { basePackage.set("com.acme.api") }
 * ```
 *
 * Wiring:
 *   - Registers `wirespecExtractor { outputDir; basePackage }` extension.
 *   - When [JavaPlugin] is applied (Kotlin-JVM applies it under the hood),
 *     registers the `extractWirespec` task using `sourceSets.main` outputs
 *     and runtime classpath, and makes `assemble` depend on it so
 *     `gradle build` always extracts.
 */
class WirespecExtractorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("wirespecExtractor", WirespecExtractorExtension::class.java).apply {
            outputDir.convention(project.layout.buildDirectory.dir("wirespec"))
            // basePackage left without a convention → null/unset means scan everything.
            extractSpring.convention(true)
            extractOpenApi.convention(true)
            extractKtor.convention(true)
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
                t.extractSpring.convention(ext.extractSpring)
                t.extractOpenApi.convention(ext.extractOpenApi)
                t.extractKtor.convention(ext.extractKtor)
                // Make sure compile runs first — classes producers wire the dependency.
                t.dependsOn(main.output.classesDirs)
            }

            // Auto-wire: every `gradle build`/`gradle assemble` runs the extractor.
            project.tasks.named("assemble") { it.dependsOn(extractTask) }
        }
    }
}
