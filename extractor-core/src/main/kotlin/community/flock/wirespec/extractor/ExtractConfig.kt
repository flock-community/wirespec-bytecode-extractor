package community.flock.wirespec.extractor

import java.io.File

/**
 * Input to [WirespecExtractor.extract].
 *
 * @property classesDirectories One or more directories of compiled `.class` files
 *   to scan. Maven projects pass one (`target/classes`); Gradle JVM projects
 *   typically pass two (`build/classes/java/main`, `build/classes/kotlin/main`).
 * @property runtimeClasspath  Additional jars and directories needed so the
 *   class loader can resolve types referenced by scanned classes.
 * @property outputDirectory   Where `.ws` files will be written.
 * @property basePackage       Optional package prefix to restrict scanning;
 *   `null` or blank means scan every package.
 * @property extractSpring     When `true` (default), extract Spring MVC
 *   controllers, functional-DSL routes, and messaging channels.
 * @property extractOpenApi    When `true` (default), extract JAX-RS resources
 *   whose OpenAPI detail is driven by swagger/OpenAPI annotations.
 * @property extractKtor       When `true` (default), extract Ktor server routing
 *   trees and Ktor client request calls.
 * @property log               Logger sink. Defaults to [ExtractLog.NoOp].
 */
data class ExtractConfig(
    val classesDirectories: List<File>,
    val runtimeClasspath: List<File>,
    val outputDirectory: File,
    val basePackage: String? = null,
    val extractSpring: Boolean = true,
    val extractOpenApi: Boolean = true,
    val extractKtor: Boolean = true,
    val log: ExtractLog = ExtractLog.NoOp,
)
