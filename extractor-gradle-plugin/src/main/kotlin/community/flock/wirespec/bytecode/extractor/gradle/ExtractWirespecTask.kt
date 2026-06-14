package community.flock.wirespec.bytecode.extractor.gradle

import community.flock.wirespec.bytecode.extractor.ExtractConfig
import community.flock.wirespec.bytecode.extractor.WirespecExtractor
import community.flock.wirespec.bytecode.extractor.WirespecExtractorException
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
