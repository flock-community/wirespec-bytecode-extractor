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
