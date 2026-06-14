package community.flock.wirespec.bytecode.extractor.maven

import community.flock.wirespec.bytecode.extractor.ExtractConfig
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

    /** Extract Spring MVC controllers, functional-DSL routes, and messaging channels. */
    @Parameter(property = "wirespec.extractSpring", defaultValue = "true")
    var extractSpring: Boolean = true

    /** Extract JAX-RS resources whose OpenAPI detail is driven by swagger annotations. */
    @Parameter(property = "wirespec.extractOpenApi", defaultValue = "true")
    var extractOpenApi: Boolean = true

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    override fun execute() {
        val runtimeClasspath: List<File> = try {
            project.runtimeClasspathElements.orEmpty().map(::File)
        } catch (_: Exception) {
            // DependencyResolutionRequiredException in unit-test contexts where
            // dependency resolution hasn't run; tolerate it and use empty classpath.
            emptyList()
        }

        try {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectories = listOf(File(project.build.outputDirectory)),
                    runtimeClasspath = runtimeClasspath,
                    outputDirectory = output,
                    basePackage = basePackage,
                    extractSpring = extractSpring,
                    extractOpenApi = extractOpenApi,
                    log = MavenExtractLog(log),
                )
            )
        } catch (e: WirespecExtractorException) {
            throw MojoExecutionException(e.message, e.cause)
        }
    }
}
