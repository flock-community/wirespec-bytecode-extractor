package community.flock.wirespec.bytecode.extractor.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Build-script DSL for the Gradle plugin:
 *
 * ```kotlin
 * wirespecExtractor {
 *     outputDir.set(layout.buildDirectory.dir("wirespec"))   // default
 *     basePackage.set("com.acme.api")
 *     extractSpring.set(true)    // default — Spring MVC, DSL routes, messaging
 *     extractOpenApi.set(true)   // default — JAX-RS + swagger annotations
 *     extractKtor.set(true)      // default — Ktor server routing + client calls
 * }
 * ```
 */
abstract class WirespecExtractorExtension {
    abstract val outputDir: DirectoryProperty
    abstract val basePackage: Property<String>

    /** Extract Spring MVC controllers, functional-DSL routes, and messaging channels. Default `true`. */
    abstract val extractSpring: Property<Boolean>

    /** Extract JAX-RS resources whose OpenAPI detail is driven by swagger annotations. Default `true`. */
    abstract val extractOpenApi: Property<Boolean>

    /** Extract Ktor server routing trees and Ktor client request calls. Default `true`. */
    abstract val extractKtor: Property<Boolean>
}
