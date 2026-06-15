package community.flock.wirespec.extractor

import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.extractor.ast.WirespecAstBuilder
import community.flock.wirespec.extractor.classpath.ClasspathBuilder
import community.flock.wirespec.extractor.emit.Emitter
import community.flock.wirespec.extractor.extract.EndpointExtractor
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.extract.dsl.DslBytecodeWalker
import community.flock.wirespec.extractor.extract.dsl.DslEndpointExtractor
import community.flock.wirespec.extractor.extract.dsl.DslRouteScanner
import community.flock.wirespec.extractor.extract.jaxrs.JaxRsEndpointExtractor
import community.flock.wirespec.extractor.extract.ktor.KtorClientExtractor
import community.flock.wirespec.extractor.extract.ktor.KtorClientScanner
import community.flock.wirespec.extractor.extract.ktor.KtorEndpointExtractor
import community.flock.wirespec.extractor.extract.ktor.KtorRoutingScanner
import community.flock.wirespec.extractor.extract.ktor.KtorRoutingWalker
import community.flock.wirespec.extractor.extract.messaging.MessagingBroker
import community.flock.wirespec.extractor.extract.messaging.MessagingChannelExtractor
import community.flock.wirespec.extractor.extract.messaging.MessagingListenerScanner
import community.flock.wirespec.extractor.extract.messaging.MessagingProducerScanner
import community.flock.wirespec.extractor.extract.messaging.MessagingProducerWalker
import community.flock.wirespec.extractor.ownership.TypeOwnership
import community.flock.wirespec.extractor.scan.ControllerScanner
import community.flock.wirespec.extractor.scan.JaxRsResourceScanner
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

        if (!config.extractSpring && !config.extractOpenApi) {
            config.log.warn("Both Spring and OpenAPI extraction are disabled; nothing to extract.")
        }

        return ClasspathBuilder.fromUrls(urls, parent = javaClass.classLoader).use { loader ->
            val controllers = if (config.extractSpring) {
                ControllerScanner.scan(
                    loader, scanPackages, effectiveBasePackage,
                    onWarn = { msg -> config.log.warn(msg) },
                ).also { config.log.info("Found ${it.size} controller(s)") }
            } else emptyList()

            val dslConfigs = if (config.extractSpring) {
                DslRouteScanner.scan(
                    loader, scanPackages, effectiveBasePackage,
                    onWarn = { msg -> config.log.warn(msg) },
                ).also {
                    if (it.isNotEmpty()) config.log.info("Found ${it.size} functional-DSL configuration(s)")
                }
            } else emptyList()

            val jaxrsResources = if (config.extractOpenApi) {
                JaxRsResourceScanner.scan(
                    loader, scanPackages, effectiveBasePackage,
                    onWarn = { msg -> config.log.warn(msg) },
                ).also {
                    if (it.isNotEmpty()) config.log.info("Found ${it.size} JAX-RS resource(s)")
                }
            } else emptyList()

            val ktorRoutingConfigs = if (config.extractKtor) {
                KtorRoutingScanner.scan(
                    loader, scanPackages, effectiveBasePackage,
                    onWarn = { msg -> config.log.warn(msg) },
                ).also {
                    if (it.isNotEmpty()) config.log.info("Found ${it.size} Ktor routing configuration(s)")
                }
            } else emptyList()

            val ktorClients = if (config.extractKtor) {
                KtorClientScanner.scan(
                    loader, scanPackages, effectiveBasePackage,
                    onWarn = { msg -> config.log.warn(msg) },
                ).also {
                    if (it.isNotEmpty()) config.log.info("Found ${it.size} Ktor client(s)")
                }
            } else emptyList()

            val collisions = detectControllerCollisions(
                controllers + dslConfigs + jaxrsResources + ktorRoutingConfigs + ktorClients
            )
            if (collisions.isNotEmpty()) {
                val msg = collisions.entries.joinToString("; ") { (name, classes) ->
                    "$name in [${classes.joinToString(", ")}]"
                }
                throw WirespecExtractorException("Controller simple-name collisions: $msg")
            }

            val types = TypeExtractor()
            val endpoints = EndpointExtractor(types, onWarn = { msg -> config.log.warn(msg) })
            val dslEndpoints = DslEndpointExtractor(types, loader, onWarn = { msg -> config.log.warn(msg) })
            val jaxrsEndpoints = JaxRsEndpointExtractor(types, onWarn = { msg -> config.log.warn(msg) })
            val ktorEndpoints = KtorEndpointExtractor(types, loader, onWarn = { msg -> config.log.warn(msg) })
            val ktorClientEndpoints = KtorClientExtractor(types, loader, onWarn = { msg -> config.log.warn(msg) })
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
            }.toMutableMap()

            for (cfg in dslConfigs) {
                val eps = try {
                    val routes = DslBytecodeWalker.walk(cfg)
                    dslEndpoints.extract(cfg, routes).map(builder::toEndpoint)
                } catch (e: WirespecExtractorException) {
                    throw e
                } catch (t: Throwable) {
                    config.log.warn("Skipping DSL ${cfg.name}: ${t.message}")
                    emptyList()
                }
                if (eps.isNotEmpty()) {
                    val key = cfg.simpleName
                    val existing = byController[key].orEmpty()
                    byController[key] = existing + eps.map { it as Definition }
                }
            }

            // -- JAX-RS resources (routing from @Path/@GET/…, OpenAPI detail from swagger) ----
            for (resource in jaxrsResources) {
                val eps = try {
                    jaxrsEndpoints.extract(resource).map(builder::toEndpoint)
                } catch (e: WirespecExtractorException) {
                    throw e
                } catch (t: Throwable) {
                    config.log.warn("Skipping JAX-RS ${resource.name}: ${t.message}")
                    emptyList()
                }
                if (eps.isNotEmpty()) {
                    val key = resource.simpleName
                    byController[key] = byController[key].orEmpty() + eps.map { it as Definition }
                }
            }

            // -- Ktor server routing (routing { } trees discovered by static bytecode walk) ----
            for (cfg in ktorRoutingConfigs) {
                val eps = try {
                    val routes = KtorRoutingWalker.walk(cfg)
                    ktorEndpoints.extract(cfg, routes).map(builder::toEndpoint)
                } catch (e: WirespecExtractorException) {
                    throw e
                } catch (t: Throwable) {
                    config.log.warn("Skipping Ktor routing ${cfg.name}: ${t.message}")
                    emptyList()
                }
                if (eps.isNotEmpty()) {
                    val key = cfg.simpleName
                    byController[key] = byController[key].orEmpty() + eps.map { it as Definition }
                }
            }

            // -- Ktor client calls (HttpClient request DSL discovered by static bytecode walk) ----
            for (clientClass in ktorClients) {
                val eps = try {
                    ktorClientEndpoints.extract(clientClass).map(builder::toEndpoint)
                } catch (e: WirespecExtractorException) {
                    throw e
                } catch (t: Throwable) {
                    config.log.warn("Skipping Ktor client ${clientClass.name}: ${t.message}")
                    emptyList()
                }
                if (eps.isNotEmpty()) {
                    val key = clientClass.simpleName
                    byController[key] = byController[key].orEmpty() + eps.map { it as Definition }
                }
            }

            // -- Messaging channels (Kafka, JMS, Rabbit, Pulsar, Spring Integration) ----
            val messagingExtractor = MessagingChannelExtractor(types, onWarn = { msg -> config.log.warn(msg) })
            for (broker in if (config.extractSpring) MessagingBroker.ALL else emptyList()) {
                val listenerSites = MessagingListenerScanner.scan(
                    loader, scanPackages, effectiveBasePackage, broker,
                    onWarn = { msg -> config.log.warn(msg) },
                )
                if (listenerSites.isNotEmpty()) {
                    config.log.info("Found ${listenerSites.size} ${broker.id} listener method(s)")
                }
                for (channel in messagingExtractor.fromListenerSites(listenerSites, broker)) {
                    val ws = builder.toChannel(channel)
                    val key = channel.ownerSimpleName
                    byController[key] = byController[key].orEmpty() + (ws as Definition)
                }

                val templateFields = MessagingProducerScanner.scan(
                    loader, scanPackages, effectiveBasePackage, broker,
                    onWarn = { msg -> config.log.warn(msg) },
                )
                if (templateFields.isNotEmpty()) {
                    config.log.info("Found ${templateFields.size} ${broker.id} template field(s)")
                }
                val producerOwners = templateFields.map { it.ownerClass }.distinct()
                val producerSites = producerOwners.flatMap { owner ->
                    MessagingProducerWalker.walk(owner, templateFields, broker, onWarn = { msg -> config.log.warn(msg) })
                }
                for (channel in messagingExtractor.fromProducerSites(producerSites)) {
                    val ws = builder.toChannel(channel)
                    val key = channel.ownerSimpleName
                    byController[key] = byController[key].orEmpty() + (ws as Definition)
                }
            }

            val byControllerFinal = byController.filterValues { it.isNotEmpty() }

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
                endpointsByController = byControllerFinal,
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
