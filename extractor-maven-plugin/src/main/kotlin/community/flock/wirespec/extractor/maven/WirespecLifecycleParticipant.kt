package community.flock.wirespec.extractor.maven

import org.apache.maven.AbstractMavenLifecycleParticipant
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.component.annotations.Component

/**
 * Maven lifecycle participant that lets users declare this plugin with just
 * `<extensions>true</extensions>` and zero `<executions>`. When the build is
 * loaded, this participant scans every project, finds our plugin declaration,
 * and (if the user didn't bind it themselves) injects a default execution that
 * runs the `extract` goal at the `process-classes` phase — i.e. immediately
 * after compile.
 *
 * Registered via `META-INF/plexus/components.xml`; the Plexus role-hint must
 * match the `hint` here.
 */
@Component(
    role = AbstractMavenLifecycleParticipant::class,
    hint = "wirespec-extractor",
)
class WirespecLifecycleParticipant : AbstractMavenLifecycleParticipant() {

    override fun afterProjectsRead(session: MavenSession) {
        session.projects.forEach { injectAutoExecution(it) }
    }

    companion object {
        const val GROUP_ID = "community.flock.wirespec.extractor"
        const val ARTIFACT_ID = "wirespec-extractor-maven-plugin"
        const val GOAL = "extract"
        const val DEFAULT_PHASE = "process-classes"
        const val DEFAULT_EXECUTION_ID = "default-extract"

        /**
         * Inject a default `extract` execution into [project]'s declaration of
         * our plugin, unless the user has already bound the goal themselves.
         * Returns true when an execution was added, false when there was
         * nothing to do (plugin not declared, or already has an extract binding).
         *
         * The plugin-level `<configuration>` is copied onto the injected
         * execution so Maven's MojoExecution sees the user's parameters.
         * (Plugin-level config doesn't automatically merge into executions
         * that are added programmatically after the POM is parsed.)
         */
        fun injectAutoExecution(project: MavenProject): Boolean {
            val plugin = findOurPlugin(project) ?: return false
            if (plugin.executions.any { e -> GOAL in e.goals }) return false
            plugin.addExecution(defaultExecution(plugin))
            return true
        }

        private fun findOurPlugin(project: MavenProject): Plugin? =
            project.build?.plugins?.firstOrNull {
                it.groupId == GROUP_ID && it.artifactId == ARTIFACT_ID
            }

        private fun defaultExecution(plugin: Plugin): PluginExecution = PluginExecution().apply {
            id = DEFAULT_EXECUTION_ID
            phase = DEFAULT_PHASE
            goals = mutableListOf(GOAL)
            configuration = plugin.configuration
        }
    }
}
