package community.flock.wirespec.extractor.maven

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.apache.maven.model.Build
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.junit.jupiter.api.Test

class WirespecLifecycleParticipantTest {

    @Test
    fun `injects default extract execution when plugin lacks executions`() {
        val project = projectWith(ourPlugin())
        val injected = WirespecLifecycleParticipant.injectAutoExecution(project)

        injected shouldBe true
        val execs = ourPluginOf(project).executions
        execs shouldHaveSize 1
        val e = execs.single()
        e.goals shouldBe listOf("extract")
        e.phase shouldBe "process-classes"
        e.id shouldBe WirespecLifecycleParticipant.DEFAULT_EXECUTION_ID
    }

    @Test
    fun `does not inject when user already declared an extract execution`() {
        val plugin = ourPlugin().apply {
            addExecution(PluginExecution().apply {
                id = "user-defined"
                phase = "package"
                goals = listOf("extract")
            })
        }
        val project = projectWith(plugin)
        val injected = WirespecLifecycleParticipant.injectAutoExecution(project)

        injected shouldBe false
        val execs = ourPluginOf(project).executions
        execs shouldHaveSize 1
        execs.single().id shouldBe "user-defined"
    }

    @Test
    fun `does not inject when our plugin is not declared in the project`() {
        val project = projectWith(Plugin().apply {
            groupId = "org.apache.maven.plugins"
            artifactId = "maven-compiler-plugin"
        })
        val injected = WirespecLifecycleParticipant.injectAutoExecution(project)
        injected shouldBe false
    }

    @Test
    fun `injected execution inherits the plugin-level configuration`() {
        // Maven's plugin-level <configuration> is not auto-merged into executions
        // that are added programmatically after the POM is parsed; the participant
        // must propagate it explicitly or the Mojo's @Parameter(required=true)
        // fields (e.g. `output`) will not be resolved.
        val pluginConfig = Xpp3Dom("configuration").apply {
            addChild(Xpp3Dom("output").apply { value = "target/wirespec" })
            addChild(Xpp3Dom("basePackage").apply { value = "com.acme.api" })
        }
        val plugin = ourPlugin().apply { configuration = pluginConfig }
        val project = projectWith(plugin)
        WirespecLifecycleParticipant.injectAutoExecution(project)

        val exec = ourPluginOf(project).executions.single()
        val cfg = exec.configuration as Xpp3Dom
        cfg.getChild("output").value shouldBe "target/wirespec"
        cfg.getChild("basePackage").value shouldBe "com.acme.api"
    }

    @Test
    fun `is idempotent — re-running the participant on an injected project is a no-op`() {
        val project = projectWith(ourPlugin())
        WirespecLifecycleParticipant.injectAutoExecution(project) shouldBe true
        WirespecLifecycleParticipant.injectAutoExecution(project) shouldBe false
        ourPluginOf(project).executions shouldHaveSize 1
    }

    private fun ourPlugin() = Plugin().apply {
        groupId = "community.flock.wirespec.extractor"
        artifactId = "wirespec-extractor-maven-plugin"
    }

    private fun projectWith(plugin: Plugin): MavenProject {
        val project = MavenProject()
        project.build = Build().apply { plugins = mutableListOf(plugin) }
        return project
    }

    private fun ourPluginOf(project: MavenProject): Plugin =
        project.build.plugins.first { it.artifactId == "wirespec-extractor-maven-plugin" }
}
