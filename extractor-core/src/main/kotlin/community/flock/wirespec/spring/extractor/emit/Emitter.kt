// src/main/kotlin/community/flock/wirespec/spring/extractor/emit/Emitter.kt
package community.flock.wirespec.spring.extractor.emit

import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Identifier
import community.flock.wirespec.compiler.core.parse.ast.Module
import community.flock.wirespec.compiler.core.parse.ast.Root
import community.flock.wirespec.compiler.utils.Logger
import community.flock.wirespec.compiler.utils.noLogger
import community.flock.wirespec.emitters.wirespec.WirespecEmitter
import java.io.File

class Emitter {

    /**
     * Custom emitter that also backticks field names starting with `_`.
     * The upstream [WirespecEmitter] already backticks names that are reserved
     * keywords or start with an uppercase letter, but Wirespec syntax also
     * requires underscore-leading field names to be quoted.
     */
    private val emitter = object : WirespecEmitter() {
        override fun emit(identifier: Identifier): String {
            if (identifier is FieldIdentifier && identifier.value.startsWith("_")) {
                return "`${identifier.value}`"
            }
            return super.emit(identifier)
        }
    }
    private val logger: Logger = noLogger

    /**
     * Render and write all `.ws` files into [outputDir].
     *
     * - Deletes existing `*.ws` files in [outputDir] recursively.
     * - Writes one `<ControllerName>.ws` per entry of [controllerDefinitions]
     *   (each entry's `List<Definition>` may contain endpoints **and** owned types).
     * - Writes one `types.ws` containing every definition in [sharedTypes].
     * - Never touches non-`.ws` files; never writes outside [outputDir].
     */
    fun write(
        outputDir: File,
        controllerDefinitions: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ): List<File> {
        outputDir.mkdirs()
        clearExistingWs(outputDir)

        val written = mutableListOf<File>()

        val (dedupedControllers, dedupedShared) = deduplicateNames(controllerDefinitions, sharedTypes)

        dedupedControllers.forEach { (controller, defs) ->
            defs.toNonEmptyListOrNull()?.let { nel ->
                val path = File(outputDir, "$controller.ws")
                path.writeText(render(nel, "$controller.ws"))
                written += path
            }
        }

        dedupedShared.toNonEmptyListOrNull()?.let { nel ->
            val path = File(outputDir, "types.ws")
            path.writeText(render(nel, "types.ws"))
            written += path
        }

        return written
    }

    private fun render(defs: NonEmptyList<Definition>, fileName: String): String {
        val ast = Root(
            modules = NonEmptyList(
                head = Module(fileUri = FileUri(fileName), statements = defs),
                tail = emptyList(),
            )
        )
        return emitter.emit(ast, logger).head.result
    }

    private fun clearExistingWs(dir: File) {
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "ws" }
            .forEach { it.delete() }
    }

    /**
     * Ensure every definition is uniquely named across *all* emitted files, not
     * just within one. A name that appears exactly once anywhere stays as-is; a
     * name that appears two or more times (endpoint↔endpoint across files,
     * endpoint↔channel, endpoint↔type, …) gets a numeric suffix on *every*
     * occurrence — `Foo1`, `Foo2`, `Foo3` — so no "winner" silently keeps the
     * bare name.
     *
     * Types are never renamed: they're referenced by name from endpoints,
     * channels, and other types (across files via `types.ws`), and renaming
     * them would break those references. Every type name is reserved up front;
     * when a type and an endpoint/channel share a name, only the
     * endpoint/channel receives a suffix.
     *
     * Renaming endpoints/channels is safe globally because nothing references
     * them — they are leaves in the reference graph.
     */
    private fun deduplicateNames(
        controllerDefinitions: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ): Pair<Map<String, List<Definition>>, List<Definition>> {
        val allDefs = controllerDefinitions.values.flatten() + sharedTypes
        val nameCounts = allDefs.groupingBy { it.identifier.value }.eachCount()
        // Reserve every type name first so a suffixed endpoint never collides
        // with a type called `Foo1` either.
        val used = allDefs
            .filter { it !is Endpoint && it !is Channel }
            .mapTo(mutableSetOf()) { it.identifier.value }

        fun rename(def: Definition): Definition {
            val name = def.identifier.value
            return when {
                def !is Endpoint && def !is Channel -> def
                nameCounts.getValue(name) == 1 -> {
                    used.add(name)
                    def
                }
                else -> {
                    var i = 1
                    while (!used.add("$name$i")) i++
                    val newName = "$name$i"
                    when (def) {
                        is Endpoint -> def.copy(identifier = DefinitionIdentifier(newName))
                        is Channel  -> def.copy(identifier = DefinitionIdentifier(newName))
                        else        -> def
                    }
                }
            }
        }

        // Assign suffixes in a deterministic order — controllers by name, then
        // shared types — so emitted names don't shuffle when the classpath scan
        // order changes between builds.
        val dedupedControllers = linkedMapOf<String, List<Definition>>()
        controllerDefinitions.keys.sorted().forEach { controller ->
            dedupedControllers[controller] = controllerDefinitions.getValue(controller).map(::rename)
        }
        val dedupedShared = sharedTypes.map(::rename)
        return dedupedControllers to dedupedShared
    }
}
