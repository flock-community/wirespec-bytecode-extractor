// src/main/kotlin/community/flock/wirespec/extractor/model/Param.kt
package community.flock.wirespec.extractor.model

data class Param(
    val name: String,
    val source: Source,
    val type: WireType,
) {
    enum class Source { PATH, QUERY, HEADER, COOKIE }
}
