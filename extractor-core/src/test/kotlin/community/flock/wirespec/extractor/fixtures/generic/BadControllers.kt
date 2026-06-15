package community.flock.wirespec.extractor.fixtures.generic

/** Holder field exposing `Page<*>` (wildcard) via reflection. */
@Suppress("unused", "UNCHECKED_CAST")
class BadControllers {
    val wildcardPage: Page<*> = Page(content = "x", totalElements = 0L, number = 0) as Page<*>
}
