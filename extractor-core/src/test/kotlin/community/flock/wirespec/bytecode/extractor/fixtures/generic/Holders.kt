package community.flock.wirespec.bytecode.extractor.fixtures.generic

import community.flock.wirespec.bytecode.extractor.fixtures.dto.Role
import community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto

/**
 * Each field's `genericType` is a `ParameterizedType` instance whose actual
 * type arguments are concrete. Tests obtain those via:
 *   FooHolder::class.java.getDeclaredField("field").genericType
 */
@Suppress("unused")
class Holders {
    val userDtoPage: Page<UserDto> = Page(content = stubUser(), totalElements = 0L, number = 0)
    val intWrapper: Wrapper<Int> = Wrapper(0)
    val stringWrapper: Wrapper<String> = Wrapper("")
    val pairUserOrder: Pair2<UserDto, Role> = Pair2(stubUser(), Role.MEMBER)
    val nestedPageOfWrapper: Page<Wrapper<UserDto>> = Page(content = Wrapper(stubUser()), totalElements = 0L, number = 0)
    val apiResponseOfList: ApiResponse<List<UserDto>> = ApiResponse(data = emptyList(), status = 200)
    val apiResponseOfMap: ApiResponse<Map<String, UserDto>> = ApiResponse(data = emptyMap(), status = 200)
    val listOfPage: List<Page<UserDto>> = emptyList()
    val userDtoTree: Tree<UserDto> = Tree(value = stubUser(), children = emptyList())
    val userDtoContainer: community.flock.wirespec.bytecode.extractor.fixtures.dto.Container<UserDto> =
        community.flock.wirespec.bytecode.extractor.fixtures.dto.Container(items = emptyList(), first = stubUser())

    private fun stubUser(): UserDto = UserDto(
        id = "x", age = 0, active = true, role = Role.MEMBER, tags = emptyList(),
    )
}
