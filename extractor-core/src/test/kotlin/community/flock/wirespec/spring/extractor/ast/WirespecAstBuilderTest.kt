// src/test/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilderTest.kt
package community.flock.wirespec.spring.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import community.flock.wirespec.spring.extractor.model.Endpoint
import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.spring.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.spring.extractor.model.Param
import community.flock.wirespec.spring.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class WirespecAstBuilderTest {

    private val builder = WirespecAstBuilder()

    @Test
    fun `endpoint has the right method, path and response status`() {
        val ep = Endpoint(
            controllerSimpleName = "UserController",
            name = "GetUser",
            method = HttpMethod.GET,
            pathSegments = listOf(
                PathSegment.Literal("users"),
                PathSegment.Variable("id", WireType.Primitive(WireType.Primitive.Kind.STRING)),
            ),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responses = listOf(Endpoint.Response(200, WireType.Ref("UserDto"))),
        )

        val ws = builder.toEndpoint(ep)
        ws.identifier.value shouldBe "GetUser"
        ws.method shouldBe WsEndpoint.Method.GET
        ws.path[0].shouldBeInstanceOf<WsEndpoint.Segment.Literal>().value shouldBe "users"
        ws.path[1].shouldBeInstanceOf<WsEndpoint.Segment.Param>().identifier.value shouldBe "id"
        ws.responses.single().status shouldBe "200"
    }

    @Test
    fun `Object becomes a Wirespec Type definition`() {
        val obj = WireType.Object(
            name = "UserDto",
            fields = listOf(
                WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING)),
                WireType.Field("age", WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)),
            ),
        )
        val def = builder.toDefinition(obj)
        def.shouldBeInstanceOf<WsType>().identifier.value shouldBe "UserDto"
        (def as WsType).shape.value.map { it.identifier.value } shouldBe listOf("id", "age")
    }

    @Test
    fun `EnumDef becomes a Wirespec Enum definition`() {
        val e = WireType.EnumDef("Role", listOf("ADMIN", "MEMBER"))
        val def = builder.toDefinition(e)
        def.shouldBeInstanceOf<WsEnum>()
        (def as WsEnum).entries shouldBe setOf("ADMIN", "MEMBER")
    }

    @Test
    fun `Refined String becomes a Wirespec Refined definition with regex`() {
        val r = WireType.Refined("RefinedABCD1234", WireType.Primitive(WireType.Primitive.Kind.STRING), regex = "^[A-Z]+$")
        val def = builder.toDefinition(r)
        def shouldBe def
        val ref = (def as community.flock.wirespec.compiler.core.parse.ast.Refined).reference
        ref.shouldBeInstanceOf<Reference.Primitive>()
        (ref.type as Reference.Primitive.Type.String).constraint?.value shouldBe "^[A-Z]+$"
    }

    @Test
    fun `nullable Ref becomes Custom with isNullable=true`() {
        val ref = builder.toReference(WireType.Ref("X", nullable = true))
        ref.shouldBeInstanceOf<Reference.Custom>()
        ref.isNullable shouldBe true
        ref.value shouldBe "X"
    }
}
