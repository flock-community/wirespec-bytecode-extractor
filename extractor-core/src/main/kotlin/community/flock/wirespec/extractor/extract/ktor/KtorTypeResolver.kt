package community.flock.wirespec.extractor.extract.ktor

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.WireType

/**
 * Resolves a JVM internal class name (recovered from an inlined `TypeInfo`) to a
 * Wirespec [WireType] via the shared [TypeExtractor]. When [rawInternal] is a
 * collection type and an element type is known, the result is a list.
 */
internal class KtorTypeResolver(
    private val types: TypeExtractor,
    private val classLoader: ClassLoader,
    private val onWarn: (String) -> Unit = {},
) {
    fun resolve(rawInternal: String?, elementInternal: String?, isList: Boolean = false): WireType? {
        if (rawInternal == null) return null
        if (KtorBytecode.isCollectionRaw(rawInternal) || isList) {
            val element = elementInternal ?: rawInternal.takeUnless { KtorBytecode.isCollectionRaw(it) }
            val elementType = element?.let { extract(it) } ?: return null
            return WireType.ListOf(elementType)
        }
        return extract(rawInternal)
    }

    private fun extract(internalName: String): WireType? {
        val cls = loadClass(internalName) ?: return null
        return try {
            types.extract(cls)
        } catch (t: Throwable) {
            onWarn("Could not extract Ktor body type ${cls.name}: ${t.message}")
            null
        }
    }

    fun loadClass(internalName: String): Class<*>? {
        val fqn = internalName.replace('/', '.')
        return try {
            Class.forName(fqn, false, classLoader)
        } catch (t: Throwable) {
            onWarn("Could not load Ktor type $fqn: ${t.message}")
            null
        }
    }
}
