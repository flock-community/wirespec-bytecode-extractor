package community.flock.wirespec.bytecode.extractor.model

/**
 * Internal domain model for a Wirespec channel. Parallel to [Endpoint], but
 * carries a single payload reference (the Wirespec `Channel` AST is single-payload).
 *
 * @property ownerSimpleName Simple name of the class that owns this channel — drives
 *   the per-class .ws file grouping used by the emitter.
 * @property name PascalCase identifier used as the Wirespec definition name.
 * @property payload Body type of the channel (consumer payload or producer value type).
 */
data class Channel(
    val ownerSimpleName: String,
    val name: String,
    val payload: WireType,
)
