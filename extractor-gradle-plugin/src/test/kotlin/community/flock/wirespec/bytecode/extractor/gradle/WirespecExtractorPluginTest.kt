package community.flock.wirespec.bytecode.extractor.gradle

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.io.File

class WirespecExtractorPluginTest {

    private fun project() = ProjectBuilder.builder().build().also {
        it.plugins.apply("community.flock.wirespec.bytecode.extractor")
    }

    @Test
    fun `applying the plugin registers the wirespecExtractor extension`() {
        val project = project()

        val extension = project.extensions.findByName("wirespecExtractor")
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
        project.plugins.apply("community.flock.wirespec.bytecode.extractor")

        val task = project.tasks.findByName("extractWirespec")
        task.shouldNotBeNull()
        (task is ExtractWirespecTask) shouldBe true
    }

    @Test
    fun `applying ours then java plugin still registers extractWirespec`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("community.flock.wirespec.bytecode.extractor")
        project.plugins.apply("java")

        project.tasks.findByName("extractWirespec").shouldNotBeNull()
    }

    @Test
    fun `extension outputDir defaults to build wirespec`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("community.flock.wirespec.bytecode.extractor")

        val ext = project.extensions.getByType(WirespecExtractorExtension::class.java)

        val expected = File(project.layout.buildDirectory.get().asFile, "wirespec")
        ext.outputDir.get().asFile shouldBe expected
    }

    @Test
    fun `assemble dependsOn extractWirespec when java plugin is applied`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        project.plugins.apply("community.flock.wirespec.bytecode.extractor")

        val assemble = project.tasks.getByName("assemble")
        val deps = assemble.taskDependencies.getDependencies(assemble).map { it.name }
        deps shouldContain "extractWirespec"
    }
}
