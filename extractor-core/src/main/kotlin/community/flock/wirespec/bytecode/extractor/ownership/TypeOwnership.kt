package community.flock.wirespec.bytecode.extractor.ownership

import community.flock.wirespec.compiler.core.parse.ast.Channel as WsChannel
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType

/**
 * Partitions extracted Wirespec definitions into "lives with one controller"
 * vs. "shared by 2+ controllers".
 *
 * A type "belongs" to a controller if it is reachable from any of that
 * controller's endpoint references (path params, queries, headers, request
 * content, response content), transitively through Object/Type field
 * references. Enums and Refined definitions are leaves in the reference graph.
 *
 * Definitions reachable from exactly one controller are appended to that
 * controller's existing endpoint list. Definitions reachable from two or more
 * controllers (or — defensively — none) end up in `shared`.
 */
internal object TypeOwnership {

    data class Partition(
        /** controllerName -> endpoint definitions (input) + owned type definitions (appended). */
        val perController: Map<String, List<Definition>>,
        /** Type definitions referenced by 2+ controllers (or zero). */
        val shared: List<Definition>,
    )

    fun partition(
        endpointsByController: Map<String, List<Definition>>,
        allTypes: List<Definition>,
        onWarn: (String) -> Unit = {},
    ): Partition {
        // 1. Index allTypes by their identifier value.
        val typeByName: Map<String, Definition> = allTypes.associateBy { it.identifier.value }

        // 2. Compute the transitive closure of Custom references per controller.
        val reachable: Map<String, Set<String>> = endpointsByController.mapValues { (_, defs) ->
            val visited = linkedSetOf<String>()
            val frontier = ArrayDeque<String>()
            defs.filterIsInstance<WsEndpoint>().forEach { ep ->
                customNamesIn(ep).forEach { name ->
                    if (visited.add(name)) frontier += name
                }
            }
            defs.filterIsInstance<WsChannel>().forEach { ch ->
                customNamesIn(ch).forEach { name ->
                    if (visited.add(name)) frontier += name
                }
            }
            while (frontier.isNotEmpty()) {
                val name = frontier.removeFirst()
                val def = typeByName[name] ?: continue
                val children = when (def) {
                    is WsType -> customNamesIn(def)
                    else      -> emptySet()  // Enum / Refined / etc. are leaves
                }
                for (child in children) if (visited.add(child)) frontier += child
            }
            visited
        }

        // 3. Invert: typeName -> set of controllers that reach it.
        val ownersByType = mutableMapOf<String, MutableSet<String>>()
        for ((controller, names) in reachable) {
            for (n in names) ownersByType.getOrPut(n) { mutableSetOf() } += controller
        }

        // 4. Partition allTypes, preserving registration order.
        val perControllerExtras = linkedMapOf<String, MutableList<Definition>>()
        val shared = mutableListOf<Definition>()
        for (def in allTypes) {
            val name = def.identifier.value
            val owners = ownersByType[name] ?: emptySet()
            when {
                owners.size == 1 -> perControllerExtras.getOrPut(owners.first()) { mutableListOf() } += def
                owners.size >= 2 -> shared += def
                else -> {
                    onWarn("Type $name has no owning controller; placing in types.ws")
                    shared += def
                }
            }
        }

        // 5. Compose: endpoints first, owned types appended.
        val merged = linkedMapOf<String, List<Definition>>()
        for ((controller, endpoints) in endpointsByController) {
            merged[controller] = endpoints + (perControllerExtras[controller].orEmpty())
        }

        return Partition(perController = merged, shared = shared)
    }

    /** Names of every `Reference.Custom` reachable from [reference]. */
    internal fun customNamesIn(reference: Reference): Sequence<String> = when (reference) {
        is Reference.Custom    -> sequenceOf(reference.value)
        is Reference.Iterable  -> customNamesIn(reference.reference)
        is Reference.Dict      -> customNamesIn(reference.reference)
        is Reference.Primitive -> emptySequence()
        is Reference.Any       -> emptySequence()
        is Reference.Unit      -> emptySequence()
    }

    /** Names of every `Reference.Custom` reachable from an endpoint's signature. */
    internal fun customNamesIn(endpoint: WsEndpoint): Set<String> {
        val out = linkedSetOf<String>()
        endpoint.path.forEach { seg ->
            if (seg is WsEndpoint.Segment.Param) out += customNamesIn(seg.reference).toList()
        }
        endpoint.queries.forEach  { f -> out += customNamesIn(f.reference).toList() }
        endpoint.headers.forEach  { f -> out += customNamesIn(f.reference).toList() }
        endpoint.requests.forEach { r -> r.content?.reference?.let { out += customNamesIn(it).toList() } }
        endpoint.responses.forEach { r ->
            r.content?.reference?.let { out += customNamesIn(it).toList() }
            r.headers.forEach { h -> out += customNamesIn(h.reference).toList() }
        }
        return out
    }

    /** Names of every `Reference.Custom` reachable from a channel's payload reference. */
    internal fun customNamesIn(channel: WsChannel): Set<String> =
        customNamesIn(channel.reference).toSet()

    /** Names of every `Reference.Custom` reachable from a Type's shape fields and extends list. */
    internal fun customNamesIn(type: WsType): Set<String> {
        val out = linkedSetOf<String>()
        type.shape.value.forEach { f -> out += customNamesIn(f.reference).toList() }
        type.extends.forEach { ref -> out += customNamesIn(ref).toList() }
        return out
    }
}
