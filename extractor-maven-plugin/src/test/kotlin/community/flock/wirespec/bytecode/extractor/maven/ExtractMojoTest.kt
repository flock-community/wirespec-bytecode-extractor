package community.flock.wirespec.bytecode.extractor.maven

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
