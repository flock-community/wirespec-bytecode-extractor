package community.flock.wirespec.bytecode.extractor.fixtures.generic

/**
 * Test fixture used to exercise generic-flattening. Field shapes are
 * deliberately simple and Jackson-friendly so the extracted wirespec
 * Object is easy to assert against.
 */
open class Page<T>(
    val content: T,
    val totalElements: Long,
    val number: Int,
)
