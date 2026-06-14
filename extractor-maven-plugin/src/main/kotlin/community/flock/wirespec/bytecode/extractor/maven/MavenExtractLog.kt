package community.flock.wirespec.bytecode.extractor.maven

import community.flock.wirespec.bytecode.extractor.ExtractLog

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
