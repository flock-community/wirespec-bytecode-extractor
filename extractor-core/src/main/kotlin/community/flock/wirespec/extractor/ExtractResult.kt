package community.flock.wirespec.extractor

import java.io.File

/**
 * Result of a successful [WirespecExtractor.extract] run.
 *
 * @property controllerCount  Number of `<Controller>.ws` files written.
 * @property sharedTypeCount  Number of definitions in the shared `types.ws`
 *   (0 means no `types.ws` was written).
 * @property filesWritten     Every `.ws` file written this run.
 */
data class ExtractResult(
    val controllerCount: Int,
    val sharedTypeCount: Int,
    val filesWritten: List<File>,
)
