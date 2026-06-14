package community.flock.wirespec.bytecode.extractor

/**
 * Logger sink consumed by [WirespecExtractor.extract]. Implementations bridge
 * this to whichever logging framework the host uses (Maven's `Log`, SLF4J,
 * stdout, etc.).
 */
interface ExtractLog {
    fun info(msg: String)
    fun warn(msg: String)

    object NoOp : ExtractLog {
        override fun info(msg: String) {}
        override fun warn(msg: String) {}
    }
}
