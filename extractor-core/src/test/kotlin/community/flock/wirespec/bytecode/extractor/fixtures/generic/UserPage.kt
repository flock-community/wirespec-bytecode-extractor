package community.flock.wirespec.bytecode.extractor.fixtures.generic

import community.flock.wirespec.bytecode.extractor.fixtures.dto.Role
import community.flock.wirespec.bytecode.extractor.fixtures.dto.UserDto

/**
 * Concrete subclass of a parameterized parent. Has its own field plus
 * the parent's `content`, `totalElements`, `number` (with T = UserDto).
 */
class UserPage : Page<UserDto>(
    content = UserDto(id = "x", age = 0, active = true, role = Role.MEMBER, tags = emptyList()),
    totalElements = 0L,
    number = 0,
) {
    val pageLabel: String = "users"
}
