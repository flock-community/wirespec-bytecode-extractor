package community.flock.wirespec.extractor.ownership

import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum
import community.flock.wirespec.compiler.core.parse.ast.Field as WsField
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Refined as WsRefined
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TypeOwnershipTest {

    @Test
    fun `empty input yields empty partition`() {
        val result = TypeOwnership.partition(
            endpointsByController = emptyMap(),
            allTypes = emptyList(),
        )
        result.perController shouldBe emptyMap()
        result.shared shouldBe emptyList()
    }

    @Test
    fun `single controller owning one type — type moves into controller`() {
        val ep = endpoint("GetUser", Reference.Custom("UserDto", false))
        val userDto = typeDef("UserDto")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("UserController" to listOf(ep)),
            allTypes = listOf(userDto),
        )

        result.perController["UserController"] shouldBe listOf(ep, userDto)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `two controllers sharing a type — type goes to shared`() {
        val epA = endpoint("GetUserA", Reference.Custom("UserDto", false))
        val epB = endpoint("GetUserB", Reference.Custom("UserDto", false))
        val userDto = typeDef("UserDto")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
            allTypes = listOf(userDto),
        )

        result.perController["A"] shouldBe listOf(epA)
        result.perController["B"] shouldBe listOf(epB)
        result.shared shouldBe listOf(userDto)
    }

    @Test
    fun `two controllers with disjoint types — each keeps its own`() {
        val epA = endpoint("GetFoo", Reference.Custom("Foo", false))
        val epB = endpoint("GetBar", Reference.Custom("Bar", false))
        val foo = typeDef("Foo")
        val bar = typeDef("Bar")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
            allTypes = listOf(foo, bar),
        )

        result.perController["A"] shouldBe listOf(epA, foo)
        result.perController["B"] shouldBe listOf(epB, bar)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `transitive ownership — nested type follows its parent into the controller`() {
        val ep = endpoint("GetUser", Reference.Custom("UserDto", false))
        val userDto = typeDef("UserDto", "address" to "Address")
        // "Address.street" → Custom("String") here exercises the unresolved-name leaf.
        val address = typeDef("Address", "street" to "String")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("UserController" to listOf(ep)),
            allTypes = listOf(userDto, address),
        )

        result.perController["UserController"] shouldBe listOf(ep, userDto, address)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `transitive promotion — nested type referenced directly by another controller moves to shared`() {
        val epA = endpoint("GetUser", Reference.Custom("UserDto", false))
        val epB = endpoint("GetAddress", Reference.Custom("Address", false))
        val userDto = typeDef("UserDto", "address" to "Address")
        val address = typeDef("Address")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
            allTypes = listOf(userDto, address),
        )

        result.perController["A"] shouldBe listOf(epA, userDto)
        result.perController["B"] shouldBe listOf(epB)
        result.shared shouldBe listOf(address)
    }

    @Test
    fun `enum shared across controllers goes to shared`() {
        val epA = endpoint("GetA", Reference.Custom("Status", false))
        val epB = endpoint("GetB", Reference.Custom("Status", false))
        val status = enumDef("Status")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("A" to listOf(epA), "B" to listOf(epB)),
            allTypes = listOf(status),
        )

        result.shared shouldBe listOf(status)
        result.perController["A"] shouldBe listOf(epA)
        result.perController["B"] shouldBe listOf(epB)
    }

    @Test
    fun `refined type with single owner is placed in that controller`() {
        val ep = endpoint("GetUser", Reference.Custom("UserDto", false))
        val userDto = typeDef("UserDto", "email" to "EmailString")
        val emailString = refinedDef("EmailString")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("UserController" to listOf(ep)),
            allTypes = listOf(userDto, emailString),
        )

        result.perController["UserController"] shouldBe listOf(ep, userDto, emailString)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `cyclic references do not loop and ownership is single-controller`() {
        val ep = endpoint("GetNode", Reference.Custom("Node", false))
        val node = typeDef("Node", "next" to "Node")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("Tree" to listOf(ep)),
            allTypes = listOf(node),
        )

        result.perController["Tree"] shouldBe listOf(ep, node)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `orphan type with no controller reference goes to shared and warns`() {
        val ep = endpoint("Get", Reference.Custom("UserDto", false))
        val userDto = typeDef("UserDto")
        val orphan = typeDef("Orphan")
        val warnings = mutableListOf<String>()

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("A" to listOf(ep)),
            allTypes = listOf(userDto, orphan),
            onWarn = { warnings += it },
        )

        result.perController["A"] shouldBe listOf(ep, userDto)
        result.shared shouldBe listOf(orphan)
        warnings.any { it.contains("Orphan") } shouldBe true
    }

    @Test
    fun `controller with no endpoints contributes nothing and owns nothing`() {
        val ep = endpoint("Get", Reference.Custom("UserDto", false))
        val userDto = typeDef("UserDto")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("Empty" to emptyList(), "A" to listOf(ep)),
            allTypes = listOf(userDto),
        )

        result.perController["Empty"] shouldBe emptyList()
        result.perController["A"] shouldBe listOf(ep, userDto)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `owned types are appended in registration order, not in reference order`() {
        val ep = endpoint("Get", Reference.Custom("B", false))  // direct ref to B first
        val a = typeDef("A")
        val b = typeDef("B", "a" to "A")  // B references A (so A is discovered after B)

        val result = TypeOwnership.partition(
            endpointsByController = mapOf("A" to listOf(ep)),
            allTypes = listOf(a, b),  // registration order: A then B
        )

        // The output preserves allTypes order: A before B.
        result.perController["A"] shouldBe listOf(ep, a, b)
    }

    // ---- Flattened-generic scenarios. From ownership's perspective a
    // ---- "flattened generic" (e.g., UserDtoPage from Page<UserDto>) is just
    // ---- a regular WireType.Object with a name and a field that references
    // ---- its concrete type-arg. These tests assert that the partition rules
    // ---- behave identically for hand-written and flattened types.

    @Test
    fun `flattened generic referenced by one controller is owned by that controller`() {
        // UserController returns UserDtoPage which references UserDto.
        val ep = endpoint("ListUsers", Reference.Custom("UserDtoPage", false))
        val userDtoPage = typeDef("UserDtoPage", "content" to "UserDto")
        val userDto = typeDef("UserDto")
        // AdminController does NOT reference UserDtoPage.
        val adminEp = endpoint("ListByRole", Reference.Custom("Role", false))
        val role = enumDef("Role")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf(
                "UserController" to listOf(ep),
                "AdminController" to listOf(adminEp),
            ),
            allTypes = listOf(userDtoPage, userDto, role),
        )

        // UserDtoPage and UserDto both flow into UserController.
        result.perController["UserController"] shouldBe listOf(ep, userDtoPage, userDto)
        result.perController["AdminController"] shouldBe listOf(adminEp, role)
        result.shared shouldBe emptyList()
    }

    @Test
    fun `flattened generic referenced by two controllers lifts to shared`() {
        val userEp = endpoint("ListUsers", Reference.Custom("UserDtoPage", false))
        val adminEp = endpoint("ListByRole", Reference.Custom("UserDtoPage", false))
        val userDtoPage = typeDef("UserDtoPage", "content" to "UserDto")
        val userDto = typeDef("UserDto")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf(
                "UserController" to listOf(userEp),
                "AdminController" to listOf(adminEp),
            ),
            allTypes = listOf(userDtoPage, userDto),
        )

        // Both flattened wrapper and its transitively-referenced UserDto lift to shared.
        result.shared shouldBe listOf(userDtoPage, userDto)
        result.perController["UserController"] shouldBe listOf(userEp)
        result.perController["AdminController"] shouldBe listOf(adminEp)
    }

    @Test
    fun `distinct flattened instantiations are each owned by their using controller`() {
        // UserController returns UserDtoPage, AdminController returns RoleDtoPage.
        val userEp = endpoint("ListUsers", Reference.Custom("UserDtoPage", false))
        val adminEp = endpoint("ListRoles", Reference.Custom("RoleDtoPage", false))
        val userDtoPage = typeDef("UserDtoPage", "content" to "UserDto")
        val roleDtoPage = typeDef("RoleDtoPage", "content" to "RoleDto")
        val userDto = typeDef("UserDto")
        val roleDto = typeDef("RoleDto")

        val result = TypeOwnership.partition(
            endpointsByController = mapOf(
                "UserController" to listOf(userEp),
                "AdminController" to listOf(adminEp),
            ),
            allTypes = listOf(userDtoPage, roleDtoPage, userDto, roleDto),
        )

        // Each flattened wrapper lives with its using controller, neither shared.
        result.perController["UserController"] shouldBe listOf(userEp, userDtoPage, userDto)
        result.perController["AdminController"] shouldBe listOf(adminEp, roleDtoPage, roleDto)
        result.shared shouldBe emptyList()
    }
}

private fun endpoint(name: String, responseRef: Reference?): WsEndpoint = WsEndpoint(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    method = WsEndpoint.Method.GET,
    path = listOf(WsEndpoint.Segment.Literal("p")),
    queries = emptyList(),
    headers = emptyList(),
    requests = listOf(WsEndpoint.Request(content = null)),
    responses = listOf(WsEndpoint.Response(
        status = "200",
        headers = emptyList(),
        content = responseRef?.let { WsEndpoint.Content("application/json", it) },
        annotations = emptyList(),
    )),
)

private fun typeDef(name: String, vararg fieldNameToRefName: Pair<String, String>): WsType = WsType(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    shape = WsType.Shape(
        value = fieldNameToRefName.map { (fn, refName) ->
            WsField(
                annotations = emptyList(),
                identifier = FieldIdentifier(fn),
                reference = Reference.Custom(refName, isNullable = false),
            )
        }
    ),
    extends = emptyList(),
)

private fun enumDef(name: String): WsEnum = WsEnum(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    entries = setOf("A", "B"),
)

@Suppress("unused")  // referenced by the refined-type test added later
private fun refinedDef(name: String): WsRefined = WsRefined(
    comment = null,
    annotations = emptyList(),
    identifier = DefinitionIdentifier(name),
    reference = Reference.Primitive(
        type = Reference.Primitive.Type.String(constraint = null),
        isNullable = false,
    ),
)
