// src/main/kotlin/community/flock/wirespec/bytecode/extractor/model/WireType.kt
package community.flock.wirespec.bytecode.extractor.model

/** Internal type model decoupled from the Wirespec AST. */
sealed interface WireType {
    val nullable: Boolean

    data class Primitive(val kind: Kind, override val nullable: Boolean = false) : WireType {
        enum class Kind { STRING, INTEGER_32, INTEGER_64, NUMBER_32, NUMBER_64, BOOLEAN, BYTES }
    }

    /** Reference to a named Object/Enum/Refined defined elsewhere. */
    data class Ref(val name: String, override val nullable: Boolean = false) : WireType

    data class ListOf(val element: WireType, override val nullable: Boolean = false) : WireType

    data class MapOf(val value: WireType, override val nullable: Boolean = false) : WireType

    /** Top-level definition: a class with named fields. */
    data class Object(
        val name: String,
        val fields: List<Field>,
        val description: String? = null,
        override val nullable: Boolean = false,
    ) : WireType

    /** Top-level definition: a finite set of string values. */
    data class EnumDef(
        val name: String,
        val values: List<String>,
        val description: String? = null,
        override val nullable: Boolean = false,
    ) : WireType

    /** Top-level definition: a refined String/Integer/Number with constraint. */
    data class Refined(
        val name: String,
        val base: Primitive,
        val regex: String? = null,
        val min: String? = null,
        val max: String? = null,
        override val nullable: Boolean = false,
    ) : WireType

    data class Field(
        val name: String,
        val type: WireType,
        val description: String? = null,
    )
}
