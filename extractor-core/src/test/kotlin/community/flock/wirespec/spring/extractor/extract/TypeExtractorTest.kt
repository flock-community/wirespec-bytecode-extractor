package community.flock.wirespec.spring.extractor.extract

import community.flock.wirespec.spring.extractor.WirespecExtractorException
import community.flock.wirespec.spring.extractor.fixtures.dto.Container
import community.flock.wirespec.spring.extractor.fixtures.dto.Role
import community.flock.wirespec.spring.extractor.fixtures.dto.TemporalDto
import community.flock.wirespec.spring.extractor.fixtures.dto.UserDto
import community.flock.wirespec.spring.extractor.model.WireType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainString
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date

class TypeExtractorTest {

    private val extractor = TypeExtractor()

    @Test
    fun `String maps to STRING primitive`() {
        extractor.extract(String::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `Int maps to INTEGER_32 primitive`() {
        extractor.extract(Int::class.javaPrimitiveType!!) shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
    }

    @Test
    fun `Long maps to INTEGER_64 primitive`() {
        extractor.extract(Long::class.javaPrimitiveType!!) shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
    }

    @Test
    fun `Boolean maps to BOOLEAN primitive`() {
        extractor.extract(Boolean::class.javaPrimitiveType!!) shouldBe WireType.Primitive(WireType.Primitive.Kind.BOOLEAN)
    }

    @Test
    fun `enum becomes Ref to its simple name and is registered as EnumDef`() {
        val ref = extractor.extract(Role::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "Role"

        val def = extractor.definitions.single { (it as? WireType.EnumDef)?.name == "Role" } as WireType.EnumDef
        def.values shouldBe listOf("ADMIN", "MEMBER")
    }

    @Test
    fun `class becomes Ref to its simple name and is registered as Object`() {
        val ref = extractor.extract(UserDto::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDto" } as WireType.Object
        obj.fields.map { it.name } shouldBe listOf("id", "age", "active", "role", "tags")
        obj.fields.first { it.name == "tags" }.type.shouldBeInstanceOf<WireType.ListOf>()
    }

    @Test
    fun `parameterized List of String becomes ListOf STRING primitive`() {
        val type = UserDto::class.java.getDeclaredField("tags").genericType
        val out = extractor.extract(type)
        out.shouldBeInstanceOf<WireType.ListOf>().element shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `extracting raw Container fails because it declares type parameters`() {
        val ex = assertThrows<WirespecExtractorException> {
            extractor.extract(Container::class.java)
        }
        ex.message!! shouldContainString "Cannot extract raw generic type Container"
    }

    @Test
    fun `Container of UserDto flattens to UserDtoContainer with substituted fields`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoContainer").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoContainer"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoContainer" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        val items = byName["items"]!!.type.shouldBeInstanceOf<WireType.ListOf>()
        items.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["first"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `recursive structure does not stack overflow (cycle cache)`() {
        // Self-referential class via reflection stub.
        val ref = extractor.extract(SelfRef::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "SelfRef"
    }

    data class SelfRef(val next: SelfRef?)

    @Test
    fun `walkFields honors @JsonProperty and @JsonIgnore`() {
        extractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.JacksonDto::class.java)
        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "JacksonDto" } as WireType.Object
        obj.fields.map { it.name } shouldBe listOf("user_id", "visible")
    }

    @Test
    fun `two classes with same simple name across packages get disambiguated names`() {
        val freshExtractor = TypeExtractor()
        val r1 = freshExtractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.clashA.Conflict::class.java)
        val r2 = freshExtractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.clashB.Conflict::class.java)
        (r1 as WireType.Ref).name shouldBe "Conflict"
        (r2 as WireType.Ref).name shouldBe "Conflict2"
        val names = freshExtractor.definitions.filterIsInstance<WireType.Object>().map { it.name }
        names shouldContainAll listOf("Conflict", "Conflict2")
    }

    @Test
    fun `LocalDateTime maps to STRING primitive and is not registered as a definition`() {
        extractor.extract(LocalDateTime::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
        extractor.definitions.map { definitionName(it) } shouldNotContain "LocalDateTime"
    }

    @Test
    fun `LocalDate maps to STRING primitive`() {
        extractor.extract(LocalDate::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `LocalTime maps to STRING primitive`() {
        extractor.extract(LocalTime::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `Instant maps to STRING primitive`() {
        extractor.extract(Instant::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `ZoneOffset maps to STRING primitive and is not registered as a definition`() {
        extractor.extract(ZoneOffset::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
        extractor.definitions.map { definitionName(it) } shouldNotContain "ZoneOffset"
    }

    @Test
    fun `ZoneId maps to STRING primitive`() {
        extractor.extract(ZoneId::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `ZonedDateTime maps to STRING primitive`() {
        extractor.extract(ZonedDateTime::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `OffsetDateTime maps to STRING primitive`() {
        extractor.extract(OffsetDateTime::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `Duration and Period map to STRING primitive`() {
        extractor.extract(Duration::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
        extractor.extract(Period::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `BigDecimal and BigInteger map to STRING primitive`() {
        extractor.extract(BigDecimal::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
        extractor.extract(BigInteger::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `URI and util Date map to STRING primitive`() {
        extractor.extract(URI::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
        extractor.extract(Date::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `DTO with JDK temporal fields produces STRING fields and no nested JDK definitions`() {
        val ref = extractor.extract(TemporalDto::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "TemporalDto"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "TemporalDto" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["createdAt"]!!.type.shouldBeInstanceOf<WireType.Primitive>().kind shouldBe WireType.Primitive.Kind.STRING
        byName["birthDate"]!!.type.shouldBeInstanceOf<WireType.Primitive>().kind shouldBe WireType.Primitive.Kind.STRING
        byName["occurredAt"]!!.type.shouldBeInstanceOf<WireType.Primitive>().kind shouldBe WireType.Primitive.Kind.STRING
        byName["timezone"]!!.type.shouldBeInstanceOf<WireType.Primitive>().kind shouldBe WireType.Primitive.Kind.STRING
        byName["zoned"]!!.type.shouldBeInstanceOf<WireType.Primitive>().kind shouldBe WireType.Primitive.Kind.STRING
        byName["price"]!!.type.shouldBeInstanceOf<WireType.Primitive>().kind shouldBe WireType.Primitive.Kind.STRING

        val defNames = extractor.definitions.map { definitionName(it) }.toSet()
        defNames shouldNotContain "LocalDateTime"
        defNames shouldNotContain "LocalDate"
        defNames shouldNotContain "Instant"
        defNames shouldNotContain "ZoneOffset"
        defNames shouldNotContain "ZonedDateTime"
        defNames shouldNotContain "BigDecimal"
    }

    @Test
    fun `List of LocalDateTime becomes ListOf STRING with no JDK definitions`() {
        val type = TemporalDtoListHolder::class.java.getDeclaredField("timestamps").genericType
        val out = extractor.extract(type)
        val list = out.shouldBeInstanceOf<WireType.ListOf>()
        list.element shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
        extractor.definitions.map { definitionName(it) } shouldNotContain "LocalDateTime"
    }

    @Suppress("unused")
    data class TemporalDtoListHolder(val timestamps: List<LocalDateTime>)

    private fun definitionName(w: WireType): String? = when (w) {
        is WireType.Object  -> w.name
        is WireType.EnumDef -> w.name
        is WireType.Refined -> w.name
        else                -> null
    }

    @Test
    fun `SchemaDto fields combine nullability, validation, and description`() {
        extractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.SchemaDto::class.java)
        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "SchemaDto" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["name"]!!.description shouldBe "The user's display name"
        byName["name"]!!.type.nullable shouldBe false
        byName["nullable"]!!.type.nullable shouldBe true
        byName["notNullablePrimitive"]!!.type.nullable shouldBe false
    }

    @Test
    fun `Optional field unwraps to a nullable inner type, not an Optional object`() {
        extractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.SchemaDto::class.java)
        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "SchemaDto" } as WireType.Object
        val maybe = obj.fields.single { it.name == "maybe" }
        maybe.type shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING, nullable = true)
        // The Optional itself must not leak as a flattened "StringOptional" object.
        extractor.definitions.none { (it as? WireType.Object)?.name == "StringOptional" } shouldBe true
    }

    @Test
    fun `JSpecify @NullMarked record defaults to non-null and honors @Nullable`() {
        extractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.jspecify.NullMarkedDto::class.java)
        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "NullMarkedDto" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["required"]!!.type.nullable shouldBe false
        byName["optional"]!!.type.nullable shouldBe true
        byName["optionalBoxed"]!!.type.nullable shouldBe true
        byName["primitive"]!!.type.nullable shouldBe false
    }

    @Test
    fun `JSpecify @Nullable and @NonNull decide per-field without @NullMarked`() {
        extractor.extract(community.flock.wirespec.spring.extractor.fixtures.dto.jspecify.PlainNullnessDto::class.java)
        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "PlainNullnessDto" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["maybe"]!!.type.nullable shouldBe true
        byName["always"]!!.type.nullable shouldBe false
        // Not null-marked → default stays nullable.
        byName["unspecified"]!!.type.nullable shouldBe true
    }

    @Test
    fun `Page of UserDto flattens to UserDtoPage with substituted fields`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoPage").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoPage" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }

        byName["content"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["totalElements"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
        byName["number"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)

        // The raw generic class is NOT registered.
        val defNames = extractor.definitions.map { definitionName(it) }.toSet()
        defNames shouldNotContain "Page"
    }

    @Test
    fun `Wrapper of Int flattens to IntegerWrapper with INTEGER_32 value field`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("intWrapper").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "IntegerWrapper"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "IntegerWrapper" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["value"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
    }

    @Test
    fun `Wrapper of String flattens to StringWrapper`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("stringWrapper").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "StringWrapper"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "StringWrapper" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["value"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `Pair2 of UserDto and Role flattens to UserDtoRolePair2 with both fields substituted`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("pairUserOrder").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoRolePair2"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoRolePair2" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["first"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["second"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "Role"
    }

    @Test
    fun `Page of Wrapper of UserDto flattens innermost first to UserDtoWrapperPage`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("nestedPageOfWrapper").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoWrapperPage"

        val names = extractor.definitions.map { definitionName(it) }.toSet()
        names shouldContain "UserDtoWrapper"
        names shouldContain "UserDtoWrapperPage"

        val outer = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoWrapperPage" } as WireType.Object
        outer.fields.single { it.name == "content" }.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoWrapper"

        val inner = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoWrapper" } as WireType.Object
        inner.fields.single { it.name == "value" }.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `ApiResponse of List of UserDto flattens to UserDtoListApiResponse with ListOf data field`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfList").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoListApiResponse"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoListApiResponse" } as WireType.Object
        val data = obj.fields.single { it.name == "data" }
        val list = data.type.shouldBeInstanceOf<WireType.ListOf>()
        list.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"

        // The bare List<UserDto> does NOT register a UserDtoList definition at use site —
        // only the wrapper's name carries the "List" suffix.
        extractor.definitions.map { definitionName(it) } shouldNotContain "UserDtoList"
    }

    @Test
    fun `ApiResponse of Map of String to UserDto flattens to UserDtoMapApiResponse with MapOf data field`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfMap").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoMapApiResponse"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoMapApiResponse" } as WireType.Object
        val data = obj.fields.single { it.name == "data" }
        val map = data.type.shouldBeInstanceOf<WireType.MapOf>()
        map.value.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
    }

    @Test
    fun `List of Page of UserDto at use site stays native ListOf with flattened element`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("listOfPage").genericType

        val out = extractor.extract(type)
        val list = out.shouldBeInstanceOf<WireType.ListOf>()
        list.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage"

        // The use-site List does NOT emit a UserDtoPageList wrapper.
        extractor.definitions.map { definitionName(it) } shouldNotContain "UserDtoPageList"
    }

    @Test
    fun `same instantiation reached twice produces one definition`() {
        val freshExtractor = TypeExtractor()
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoPage").genericType

        freshExtractor.extract(type)
        freshExtractor.extract(type)

        val count = freshExtractor.definitions.count { (it as? WireType.Object)?.name == "UserDtoPage" }
        count shouldBe 1
    }

    @Test
    fun `extracting a class that extends a raw generic parent fails with a clear error`() {
        val ex = assertThrows<WirespecExtractorException> {
            extractor.extract(community.flock.wirespec.spring.extractor.fixtures.generic.RawSuperPage::class.java)
        }
        ex.message!! shouldContainString "RawSuperPage extends generic Page"
        ex.message!! shouldContainString "Page<UserDto>"
    }

    @Test
    fun `extracting a generic with a wildcard argument fails with a clear error`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.BadControllers::class.java
            .getDeclaredField("wildcardPage").genericType

        val ex = assertThrows<WirespecExtractorException> {
            extractor.extract(type)
        }
        ex.message!! shouldContainString "Wildcard type argument"
        ex.message!! shouldContainString "replace with a concrete type"
    }

    @Test
    fun `extracting a raw generic class fails with a clear error`() {
        val ex = assertThrows<WirespecExtractorException> {
            extractor.extract(community.flock.wirespec.spring.extractor.fixtures.generic.Page::class.java)
        }
        ex.message!! shouldContainString "Cannot extract raw generic type Page"
        ex.message!! shouldContainString "provide a concrete type argument"
    }

    @Test
    fun `hand-written UserDtoPage and flattened Page of UserDto are disambiguated deterministically`() {
        val freshExtractor = TypeExtractor()

        // Register the hand-written class first; it claims "UserDtoPage".
        val handRef = freshExtractor.extract(
            community.flock.wirespec.spring.extractor.fixtures.generic.UserDtoPage::class.java
        )
        handRef.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage"

        // Then extract Page<UserDto>; it gets the numeric suffix.
        val flatRef = freshExtractor.extract(
            community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
                .getDeclaredField("userDtoPage").genericType
        )
        flatRef.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoPage2"

        val names = freshExtractor.definitions.map { definitionName(it) }
        names shouldContainAll listOf("UserDtoPage", "UserDtoPage2")
    }

    @Test
    fun `UserPage subclass of Page of UserDto extracts to Ref UserPage with inherited fields substituted`() {
        val ref = extractor.extract(community.flock.wirespec.spring.extractor.fixtures.generic.UserPage::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserPage"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserPage" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }

        // Inherited fields, parent-first ordering.
        byName["content"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"
        byName["totalElements"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
        byName["number"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)

        // Own field.
        byName["pageLabel"]!!.type shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)

        // Field ordering: parent fields first, then declared.
        val parentFields = listOf("content", "totalElements", "number")
        val ownFields = listOf("pageLabel")
        obj.fields.map { it.name } shouldBe parentFields + ownFields
    }

    @Test
    fun `Tree of UserDto flattens to UserDtoTree without infinite recursion`() {
        val type = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoTree").genericType

        val ref = extractor.extract(type)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoTree"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDtoTree" } as WireType.Object
        val byName = obj.fields.associateBy { it.name }
        byName["value"]!!.type.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"

        // `children: List<Tree<T>>` -> ListOf(Ref("UserDtoTree")) via the cached placeholder.
        val children = byName["children"]!!.type.shouldBeInstanceOf<WireType.ListOf>()
        children.element.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDtoTree"

        // Exactly one UserDtoTree definition.
        extractor.definitions.count { (it as? WireType.Object)?.name == "UserDtoTree" } shouldBe 1
    }

    @Test
    fun `distinct flattened instantiations are each registered`() {
        val freshExtractor = TypeExtractor()
        val userPage = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("userDtoPage").genericType
        val arOfList = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfList").genericType
        val arOfMap = community.flock.wirespec.spring.extractor.fixtures.generic.Holders::class.java
            .getDeclaredField("apiResponseOfMap").genericType

        freshExtractor.extract(userPage)
        freshExtractor.extract(arOfList)
        freshExtractor.extract(arOfMap)

        val names = freshExtractor.definitions.map { definitionName(it) }.toSet()
        names shouldContainAll listOf("UserDtoPage", "UserDtoListApiResponse", "UserDtoMapApiResponse")
    }
}
