package community.flock.wirespec.spring.extractor

import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.spring.extractor.ast.WirespecAstBuilder
import community.flock.wirespec.spring.extractor.classpath.ClasspathBuilder
import community.flock.wirespec.spring.extractor.emit.Emitter
import community.flock.wirespec.spring.extractor.extract.EndpointExtractor
import community.flock.wirespec.spring.extractor.extract.TypeExtractor
import community.flock.wirespec.spring.extractor.ownership.TypeOwnership
import community.flock.wirespec.spring.extractor.scan.ControllerScanner
import java.io.File

/**
 * Maven-agnostic entry point for the Spring → Wirespec extractor.
 *
 * Scans [ExtractConfig.classesDirectory] for Spring controllers and writes
 * `<Controller>.ws` + (optional) `types.ws` files to
 * [ExtractConfig.outputDirectory].
 */
object WirespecExtractor {

    /**
     * @throws WirespecExtractorException if the classes directory is missing
     *   or empty, the output directory is not writable, or two controllers
     *   share a simple name.
     */
    fun extract(config: ExtractConfig): ExtractResult {
        val classesDirs = config.classesDirectories
        val hasAnyClasses = classesDirs.any { it.exists() && !it.listFiles().isNullOrEmpty() }
        if (!hasAnyClasses) {
            val paths = classesDirs.joinToString(", ") { it.absolutePath }
            throw WirespecExtractorException(
                "No compiled classes in $paths. Did compilation run before extraction?"
            )
        }
        assertOutputWritable(config.outputDirectory)

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = config.runtimeClasspath.map { it.absolutePath },
            outputDirectories = classesDirs,
        )

        val effectiveBasePackage = effectiveBasePackage(config.basePackage)
        val scanPackages = listOfNotNull(effectiveBasePackage)

        return ClasspathBuilder.fromUrls(urls, parent = javaClass.classLoader).use { loader ->
            val controllers = ControllerScanner.scan(
                loader, scanPackages, effectiveBasePackage,
                onWarn = { msg -> config.log.warn(msg) },
            )
            config.log.info("Found ${controllers.size} controller(s)")

            val collisions = detectControllerCollisions(controllers)
            if (collisions.isNotEmpty()) {
                val msg = collisions.entries.joinToString("; ") { (name, classes) ->
                    "$name in [${classes.joinToString(", ")}]"
                }
                throw WirespecExtractorException("Controller simple-name collisions: $msg")
            }

            val types = TypeExtractor()
            val endpoints = EndpointExtractor(types, onWarn = { msg -> config.log.warn(msg) })
            val builder = WirespecAstBuilder()

            val byController = controllers.associate { c ->
                val eps = try {
                    endpoints.extract(c).map(builder::toEndpoint)
                } catch (e: WirespecExtractorException) {
                    throw e  // user-facing errors (e.g., generic-flattening) must fail the build
                } catch (t: Throwable) {
                    config.log.warn("Skipping ${c.name}: ${t.message}")
                    emptyList()
                }
                c.simpleName to eps.map { it as Definition }
            }.filterValues { it.isNotEmpty() }

            val allTypes = types.definitions.mapNotNull { def ->
                try {
                    builder.toDefinition(def)
                } catch (e: WirespecExtractorException) {
                    throw e
                } catch (t: Throwable) {
                    config.log.warn("Skipping type ${def}: ${t.message}")
                    null
                }
            }

            val partition = TypeOwnership.partition(
                endpointsByController = byController,
                allTypes = allTypes,
                onWarn = { msg -> config.log.warn(msg) },
            )

            val filesWritten = Emitter().write(
                outputDir = config.outputDirectory,
                controllerDefinitions = partition.perController,
                sharedTypes = partition.shared,
            )
            config.log.info(
                "Wrote ${partition.perController.size + (if (partition.shared.isEmpty()) 0 else 1)} .ws file(s) to ${config.outputDirectory.absolutePath}"
            )

            ExtractResult(
                controllerCount = partition.perController.size,
                sharedTypeCount = partition.shared.size,
                filesWritten = filesWritten,
            )
        }
    }
}

/** Returns a map of simple name -> list of FQNs for controllers sharing the same simple name. */
internal fun detectControllerCollisions(controllers: List<Class<*>>): Map<String, List<String>> =
    controllers.groupBy { it.simpleName }
        .filterValues { it.size > 1 }
        .mapValues { (_, classes) -> classes.map { it.name } }

/** Normalises the raw `basePackage` parameter: blank or null becomes null. */
internal fun effectiveBasePackage(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

/**
 * Asserts that [output] (or the nearest existing ancestor) is writable.
 * Throws [WirespecExtractorException] otherwise.
 */
internal fun assertOutputWritable(output: File) {
    val existing = generateSequence(output) { it.parentFile }.firstOrNull { it.exists() }
        ?: throw WirespecExtractorException("No writable ancestor for output: ${output.absolutePath}")
    if (!existing.canWrite()) {
        throw WirespecExtractorException("Output dir not writable: ${existing.absolutePath}")
    }
}
