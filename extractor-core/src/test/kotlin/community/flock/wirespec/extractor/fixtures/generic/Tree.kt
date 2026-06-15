package community.flock.wirespec.extractor.fixtures.generic

class Tree<T>(
    val value: T,
    val children: List<Tree<T>>,
)
