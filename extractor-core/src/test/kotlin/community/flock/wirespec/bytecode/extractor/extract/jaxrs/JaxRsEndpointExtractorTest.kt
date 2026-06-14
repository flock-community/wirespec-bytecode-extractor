package community.flock.wirespec.bytecode.extractor.extract.jaxrs

import community.flock.wirespec.bytecode.extractor.extract.TypeExtractor
import community.flock.wirespec.bytecode.extractor.fixtures.jaxrs.UserResource
import community.flock.wirespec.bytecode.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.bytecode.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.bytecode.extractor.model.Param
import community.flock.wirespec.bytecode.extractor.model.WireType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JaxRsEndpointExtractorTest {

    private fun extract() =
        JaxRsEndpointExtractor(TypeExtractor()).extract(UserResource::class.java)

    @Test
    fun `routes from JAX-RS path and HTTP-method annotations`() {
        val byName = extract().associateBy { it.name }

        byName.keys shouldContainExactly setOf("ListUsers", "GetOne", "CreateUser", "Delete")

        byName.getValue("ListUsers").method shouldBe HttpMethod.GET
        byName.getValue("CreateUser").method shouldBe HttpMethod.POST
        byName.getValue("Delete").method shouldBe HttpMethod.DELETE

        // class-level @Path("/users") + method-level @Path("/{id}")
        byName.getValue("GetOne").pathSegments shouldBe listOf(
            PathSegment.Literal("users"),
            PathSegment.Variable("id", WireType.Primitive(WireType.Primitive.Kind.STRING)),
        )
        byName.getValue("ListUsers").pathSegments shouldBe listOf(PathSegment.Literal("users"))
    }

    @Test
    fun `names come from swagger operationId, falling back to the method name`() {
        val names = extract().map { it.name }
        // @Operation(operationId = "listUsers") -> ListUsers; no operationId on getOne -> GetOne
        names.contains("ListUsers") shouldBe true
        names.contains("GetOne") shouldBe true
    }

    @Test
    fun `binds query, header, path params and the entity body`() {
        val byName = extract().associateBy { it.name }

        val list = byName.getValue("ListUsers")
        list.queryParams.map { it.name } shouldContainExactly listOf("active")
        list.headerParams.map { it.name } shouldContainExactly listOf("X-Trace")

        // Optional Kotlin params are nullable.
        list.queryParams.single { it.name == "active" }.type.nullable shouldBe true

        val create = byName.getValue("CreateUser")
        create.queryParams.isEmpty() shouldBe true
        // The unannotated parameter is the JAX-RS request entity.
        (create.requestBody as WireType.Ref).name shouldBe "CreateUserDto"

        val getOne = byName.getValue("GetOne")
        getOne.pathSegments.filterIsInstance<PathSegment.Variable>().map { it.name } shouldContainExactly listOf("id")
        getOne.queryParams.isEmpty() shouldBe true
    }

    @Test
    fun `reads swagger ApiResponses for multiple statuses`() {
        val getOne = extract().single { it.name == "GetOne" }
        val byStatus = getOne.responses.associateBy { it.statusCode }

        byStatus.keys shouldContainExactly setOf(200, 404)
        (byStatus.getValue(200).body as WireType.Ref).name shouldBe "UserDto"
        (byStatus.getValue(404).body as WireType.Ref).name shouldBe "ErrorDto"
    }

    @Test
    fun `void handler yields a 204 with no body`() {
        val delete = extract().single { it.name == "Delete" }
        delete.responses shouldContainExactly listOf(
            community.flock.wirespec.bytecode.extractor.model.Endpoint.Response(204, null)
        )
    }

    @Test
    fun `list return type becomes a list-of body`() {
        val list = extract().single { it.name == "ListUsers" }
        val body = list.responses.single { it.statusCode == 200 }.body
        val element = (body as WireType.ListOf).element as WireType.Ref
        element.name shouldBe "UserDto"
    }

    @Test
    fun `query params land in the queries bucket, not the path`() {
        val list = extract().single { it.name == "ListUsers" }
        list.queryParams.single().source shouldBe Param.Source.QUERY
    }
}
