package community.flock.wirespec.spring.extractor.extract

import community.flock.wirespec.spring.extractor.WirespecExtractorException
import community.flock.wirespec.spring.extractor.model.WireType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.Optional
import java.util.UUID

/**
 * Walks Java/Kotlin types and produces internal WireType references plus
 * a registered set of top-level definitions (Object/Enum/Refined).
 *
 * One TypeExtractor instance is used per scan run so the `definitions` set
 * accumulates across all controllers.
 */
open class TypeExtractor {

    private val cache = mutableMapOf<String, WireType>()
    protected val _definitions = linkedSetOf<WireType>()
    /** Tracks which simple name has been claimed by which FQN to detect cross-package collisions. */
    private val usedNames = mutableMapOf<String, String>()  // simpleName -> identity that claimed it

    /** Stack of TypeVariable -> Type bindings, pushed when entering a parameterized type's body. */
    private val bindings = ArrayDeque<Map<TypeVariable<*>, Type>>()

    val definitions: Set<WireType> get() = _definitions

    /**
     * Extract a [WireType] for [type], stamping the top-level reference with [nullable].
     * Top-level Object/Enum/Refined definitions accumulate in [definitions].
     */
    fun extract(type: Type, nullable: Boolean = false): WireType =
        withNullability(extractInner(type, nullable = false), nullable)

    private fun extractInner(type: Type, nullable: Boolean): WireType = when (type) {
        is Class<*>          -> fromClass(type, nullable)
        is ParameterizedType -> fromParameterized(type, nullable)
        is WildcardType      -> extractInner(type.upperBounds.firstOrNull() ?: Any::class.java, nullable)
        is TypeVariable<*>   -> resolveBinding(type)
            ?.let { extractInner(it, nullable) }
            ?: WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        else                 -> WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
    }

    /**
     * Returns a unique name for [cls], disambiguating with a numeric suffix when two classes
     * from different packages share the same simple name (e.g. com.a.User and com.b.User → "User", "User2").
     */
    private fun nameFor(cls: Class<*>): String {
        val simple = cls.simpleName
        val existing = usedNames[simple]
        return when {
            existing == null -> { usedNames[simple] = cls.name; simple }
            existing == cls.name -> simple
            else -> {
                // collision — find or assign a numeric suffix
                var i = 2
                while (usedNames["$simple$i"] != null && usedNames["$simple$i"] != cls.name) i++
                val newName = "$simple$i"
                if (usedNames[newName] == null) usedNames[newName] = cls.name
                newName
            }
        }
    }

    /**
     * Like [nameFor] but for a composed flat name (e.g., "UserDtoPage" from Page<UserDto>).
     * The collision key is `${rawClass.name}#$composed` so a hand-written class registered
     * under the plain FQN cannot match a flattened-generic identity, and the flattened
     * generic gets a numeric suffix.
     */
    private fun nameFor(composed: String, rawClass: Class<*>): String {
        val identity = "${rawClass.name}#$composed"
        val existing = usedNames[composed]
        return when {
            existing == null -> { usedNames[composed] = identity; composed }
            existing == identity -> composed
            else -> {
                var i = 2
                while (usedNames["$composed$i"] != null && usedNames["$composed$i"] != identity) i++
                val newName = "$composed$i"
                if (usedNames[newName] == null) usedNames[newName] = identity
                newName
            }
        }
    }

    private fun fromClass(cls: Class<*>, nullable: Boolean): WireType {
        primitiveOf(cls)?.let { return it.copy(nullable = nullable) }
        if (cls == String::class.java) return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        if (cls == ByteArray::class.java) return WireType.Primitive(WireType.Primitive.Kind.BYTES, nullable)
        if (cls == UUID::class.java) return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        // Raw Optional (no type argument) — an opaque, inherently nullable value.
        if (cls == Optional::class.java) return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable = true)
        if (Enum::class.java.isAssignableFrom(cls)) {
            val fp = cls.name
            cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
            val name = nameFor(cls)
            val ref = WireType.Ref(name, nullable)
            cache[fp] = ref.copy(nullable = false)
            @Suppress("UNCHECKED_CAST")
            val values = (cls.enumConstants as Array<Enum<*>>).map { it.name }
            _definitions += WireType.EnumDef(name, values)
            return ref
        }
        if (Collection::class.java.isAssignableFrom(cls)) {
            return WireType.ListOf(WireType.Primitive(WireType.Primitive.Kind.STRING), nullable)
        }
        // JDK / platform value types (java.time.*, java.math.BigDecimal, java.net.URI, …):
        // walking their declared fields would leak internal implementation details
        // (e.g. ZoneOffset's `totalSeconds`) into the Wirespec schema. Jackson serializes
        // these to opaque strings, so represent them as STRING here too.
        if (isJdkOpaqueType(cls)) {
            return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        }
        // A raw generic class reached as a reference cannot be flattened because there are
        // no type arguments to bind. This is a user-fixable error in the controller signature.
        if (cls.typeParameters.isNotEmpty()) {
            throw WirespecExtractorException.rawGenericReference(
                rawClass = cls.name,
                controllerMethod = currentContext(),
            )
        }
        // Object class — register and recurse into its fields.
        val fp = cls.name
        cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
        val name = nameFor(cls)
        val ref = WireType.Ref(name, nullable)
        cache[fp] = ref.copy(nullable = false)
        val fields = walkFields(cls)
        _definitions += WireType.Object(name, fields)
        return ref
    }

    private fun fromParameterized(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>
        // Optional<T> is a nullability wrapper, not a DTO: unwrap to T (always nullable)
        // instead of flattening it into an "TOptional" object with Optional's internals.
        if (raw == Optional::class.java) {
            return withNullability(extractInner(pt.actualTypeArguments[0], nullable = false), nullable = true)
        }
        if (Collection::class.java.isAssignableFrom(raw)) {
            // Let extractInner handle the element type, including TypeVariable resolution
            // via the bindings stack. The previous TypeVariable -> STRING short-circuit
            // bypassed bindings and broke generic flattening's inherited-field case.
            val element = extractInner(pt.actualTypeArguments[0], nullable = false)
            return WireType.ListOf(element, nullable)
        }
        if (Map::class.java.isAssignableFrom(raw)) {
            val v = extractInner(pt.actualTypeArguments[1], nullable = false)
            return WireType.MapOf(v, nullable)
        }
        // User-level generic — flatten.
        return flattenGeneric(pt, nullable)
    }

    /**
     * Flatten a `Page<UserDto>`-style parameterized type into a uniquely-named
     * Object definition with type-substituted fields.
     */
    private fun flattenGeneric(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>

        // Reject wildcards in args (Page<?>, Page<? extends X>) — they have no single concrete binding.
        // Doing this up-front gives a clearer error message (mentions the full parameterized type)
        // than letting it surface inside flatName().
        for (arg in pt.actualTypeArguments) {
            if (arg is WildcardType) {
                throw WirespecExtractorException.wildcardArgument(
                    atType = pt.typeName,
                    controllerMethod = currentContext(),
                )
            }
        }

        val fp = fingerprint(pt)

        // Same instantiation reached twice -> same ref.
        cache[fp]?.let { return (it as WireType.Ref).copy(nullable = nullable) }

        // Compose the display name from resolved args, then disambiguate.
        val composedName = flatName(pt)
        val name = nameFor(composedName, raw)

        // Insert placeholder ref BEFORE walking fields so self-referential
        // generics terminate (Tree<UserDto> -> children: List<Tree<UserDto>>).
        val ref = WireType.Ref(name, nullable)
        cache[fp] = ref.copy(nullable = false)

        // Build binding frame: typeParameter -> actualTypeArgument.
        @Suppress("UNCHECKED_CAST")
        val frame = raw.typeParameters
            .zip(pt.actualTypeArguments)
            .toMap() as Map<TypeVariable<*>, Type>
        bindings.addFirst(frame)
        try {
            val fields = walkFields(raw)
            _definitions += WireType.Object(name, fields)
        } finally {
            bindings.removeFirst()
        }
        return ref
    }

    /**
     * Stable fingerprint for cache lookup. Uses FQNs so that display-name
     * disambiguation (`UserDtoPage` vs `UserDtoPage2`) cannot confuse the cache.
     */
    private fun fingerprint(type: Type): String = when (type) {
        is Class<*> -> type.name
        is ParameterizedType -> {
            val raw = (type.rawType as Class<*>).name
            val args = type.actualTypeArguments.joinToString(",") { fingerprint(it) }
            "$raw($args)"
        }
        is WildcardType -> "?"
        is TypeVariable<*> -> resolveBinding(type)?.let { fingerprint(it) } ?: "T:${type.name}"
        else -> type.typeName
    }

    /** Walk the bindings stack outside-in to resolve [variable]. */
    private fun resolveBinding(variable: TypeVariable<*>): Type? {
        for (frame in bindings) {
            frame[variable]?.let { return it }
        }
        return null
    }

    /**
     * Display name for a type as it appears inside a flattened wrapper name.
     * Composed names get funneled through [nameFor] for collision handling.
     */
    private fun flatName(type: Type): String = when (type) {
        is Class<*> -> when {
            primitiveOf(type) != null -> type.simpleName
            type == String::class.java -> "String"
            type == ByteArray::class.java -> "ByteArray"
            type == java.util.UUID::class.java -> "UUID"
            else -> type.simpleName
        }
        is ParameterizedType -> {
            val raw = type.rawType as Class<*>
            val args = type.actualTypeArguments
            when {
                Collection::class.java.isAssignableFrom(raw) -> flatName(args[0]) + "List"
                Map::class.java.isAssignableFrom(raw)        -> flatName(args[1]) + "Map"
                else -> args.joinToString("") { flatName(it) } + raw.simpleName
            }
        }
        is TypeVariable<*> -> resolveBinding(type)
            ?.let { flatName(it) }
            ?: throw WirespecExtractorException.unboundTypeVariable(
                variable = type.name,
                inClassField = "<flatName>",
                controllerMethod = currentContext(),
            )
        is WildcardType -> throw WirespecExtractorException.wildcardArgument(
            atType = type.typeName,
            controllerMethod = currentContext(),
        )
        else -> type.typeName
    }

    /**
     * Best-effort description of the originating controller method for error
     * messages. Until controller context is threaded through this returns
     * a sentinel; the static parts of error messages still hold.
     */
    private fun currentContext(): String = "<unknown>"

    /**
     * Walk the inheritance chain of [cls] producing one [WireType.Field] per
     * (non-ignored, non-shadowed) property, parent-first. When a level's
     * declared superclass is parameterized, the corresponding type-variable
     * bindings are pushed onto [bindings] *before* the parent's fields are
     * extracted, and popped after. This keeps inherited-field T-references
     * resolving correctly (e.g., UserPage : Page<UserDto> resolves `content: T`
     * to `UserDto`).
     *
     * Override-able by subclasses to inject extra processing per level.
     */
    protected open fun walkFields(cls: Class<*>): List<WireType.Field> {
        // chain[0] = leaf, chain[chain.size - 1] = topmost non-Object ancestor
        val chain = mutableListOf<Class<*>>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java && c != Object::class.java) {
            chain.add(c)
            c = c.superclass
        }

        // Pre-compute shadowing: names declared by any descendant of level i
        // (descendants are chain[0..i-1] in leaf-first order).
        val perLevelNames = chain.map { propertyMembers(it).map { p -> p.first }.toSet() }
        val shadowedAt = Array(chain.size) { i ->
            val s = mutableSetOf<String>()
            for (j in 0 until i) s.addAll(perLevelNames[j])
            s
        }

        // Walk root-to-leaf so parent fields appear first in the output.
        // Push binding frames as we descend (chain[i+1]'s genericSuperclass binds chain[i]'s
        // type parameters — but with our chain ordering, the leaf is chain[0], its parent is
        // chain[1], so descending root-to-leaf means iterating chain[size-1] down to chain[0].
        // The frame to push BEFORE processing chain[i] comes from chain[i-1].genericSuperclass.
        val pushedFrames = mutableListOf<Boolean>()
        val out = mutableListOf<WireType.Field>()
        val seen = mutableSetOf<String>()

        try {
            for (i in chain.indices.reversed()) {
                val level = chain[i]
                // If this level has a deeper descendant (chain[i-1] in leaf-first order, i.e.,
                // smaller index), push the frame from THAT descendant's genericSuperclass.
                // I.e., for chain = [UserPage(0), Page(1)], processing Page (i=1):
                //   descendant = chain[0] = UserPage, its genericSuperclass is Page<UserDto>.
                //   Push {Page.T: UserDto}.
                val pushed = if (i > 0) {
                    val descendant = chain[i - 1]
                    val gen = descendant.genericSuperclass
                    val rawSuper = descendant.superclass
                    when {
                        gen is ParameterizedType -> {
                            val parentRaw = gen.rawType as Class<*>
                            @Suppress("UNCHECKED_CAST")
                            val frame = parentRaw.typeParameters
                                .zip(gen.actualTypeArguments)
                                .toMap() as Map<TypeVariable<*>, Type>
                            bindings.addFirst(frame)
                            true
                        }
                        rawSuper != null
                            && rawSuper != Any::class.java
                            && rawSuper != Object::class.java
                            && rawSuper.typeParameters.isNotEmpty() -> {
                            // class X extends Page  (raw): inherited type vars would
                            // collapse silently. Reject.
                            throw WirespecExtractorException.rawGenericSuperclass(
                                subclassName = descendant.simpleName,
                                rawSuperclassName = rawSuper.simpleName,
                            )
                        }
                        else -> false
                    }
                } else false
                pushedFrames.add(pushed)

                for ((name, type) in propertyMembers(level)) {
                    if (name in shadowedAt[i]) continue
                    if (!seen.add(name)) continue
                    val field = level.declaredFieldOrNull(name)
                    val element: java.lang.reflect.AnnotatedElement = field ?: level
                    if (JacksonNames.isIgnored(element)) continue

                    val declaredClass = (type as? Class<*>) ?: ((type as? ParameterizedType)?.rawType as? Class<*>) ?: Any::class.java
                    val nullable = NullabilityResolver.isNullable(element, declaredClass)

                    val rawType = withNullability(extractInner(type, nullable = false), nullable)
                    val refined = ValidationConstraints.refine(element, rawType)
                    if (refined is WireType.Refined) _definitions += refined

                    out += WireType.Field(
                        name = JacksonNames.effectiveName(element, original = name),
                        type = if (refined is WireType.Refined) WireType.Ref(refined.name, nullable) else refined,
                        description = NullabilityResolver.schemaDescription(element),
                    )
                }
            }
        } finally {
            for (pushed in pushedFrames.reversed()) if (pushed) bindings.removeFirst()
        }

        return out
    }

    private fun withNullability(t: WireType, nullable: Boolean): WireType = when (t) {
        is WireType.Primitive -> t.copy(nullable = nullable)
        is WireType.Ref       -> t.copy(nullable = nullable)
        is WireType.ListOf    -> t.copy(nullable = nullable)
        is WireType.MapOf     -> t.copy(nullable = nullable)
        is WireType.Object    -> t.copy(nullable = nullable)
        is WireType.EnumDef   -> t.copy(nullable = nullable)
        is WireType.Refined   -> t.copy(nullable = nullable)
    }

    private fun Class<*>.declaredFieldOrNull(name: String): java.lang.reflect.Field? =
        try { getDeclaredField(name) } catch (_: NoSuchFieldException) { null }

    /**
     * Discover (name, generic-type) pairs for a class:
     * 1. Java records: record components.
     * 2. Kotlin data classes: declared fields (Kotlin generates fields for properties).
     * 3. JavaBeans: getters paired with backing fields.
     */
    protected fun propertyMembers(cls: Class<*>): List<Pair<String, Type>> {
        if (cls.isRecord) {
            return cls.recordComponents.map { it.name to it.genericType }
        }
        // Kotlin data classes / POJOs: prefer declared non-static, non-synthetic fields.
        val declared = cls.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name to it.genericType }
        if (declared.isNotEmpty()) return declared
        // Pure JavaBean: getXxx / isXxx pairs.
        return cls.methods
            .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) && it.declaringClass != Any::class.java }
            .filter { it.name != "getClass" }
            .map { method ->
                val raw = method.name.removePrefix("get").removePrefix("is")
                val name = raw.replaceFirstChar { it.lowercase() }
                name to method.genericReturnType
            }
    }

    /**
     * Whether [cls] is a JDK / platform value type that should be treated as an
     * opaque STRING in Wirespec instead of being expanded into a nested object.
     *
     * Covers `java.time.*` (LocalDateTime, ZoneOffset, Instant, …), `java.math.*`
     * (BigDecimal, BigInteger), `java.net.URI/URL`, `java.util.Date`/`Calendar`,
     * `java.sql.*` temporal types, and anything else shipped in the `java.*` /
     * `jdk.*` / `sun.*` namespaces that we haven't already mapped above
     * (primitives, String, ByteArray, UUID, Enum, Collection).
     */
    private fun isJdkOpaqueType(cls: Class<*>): Boolean {
        val pkg = cls.`package`?.name ?: return false
        return pkg == "java.lang"
            || pkg.startsWith("java.")
            || pkg.startsWith("javax.")
            || pkg.startsWith("jakarta.")
            || pkg.startsWith("jdk.")
            || pkg.startsWith("sun.")
            || pkg.startsWith("com.sun.")
    }

    private fun primitiveOf(cls: Class<*>): WireType.Primitive? = when (cls) {
        Int::class.javaPrimitiveType, Integer::class.java        -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
        Short::class.javaPrimitiveType, java.lang.Short::class.java -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java   -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> WireType.Primitive(WireType.Primitive.Kind.NUMBER_32)
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> WireType.Primitive(WireType.Primitive.Kind.NUMBER_64)
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> WireType.Primitive(WireType.Primitive.Kind.BOOLEAN)
        Char::class.javaPrimitiveType, java.lang.Character::class.java -> WireType.Primitive(WireType.Primitive.Kind.STRING)
        else -> null
    }
}
