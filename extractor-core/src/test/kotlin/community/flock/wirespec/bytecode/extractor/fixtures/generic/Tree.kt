package community.flock.wirespec.bytecode.extractor.fixtures.generic

class Tree<T>(
    val value: T,
    val children: List<Tree<T>>,
)
