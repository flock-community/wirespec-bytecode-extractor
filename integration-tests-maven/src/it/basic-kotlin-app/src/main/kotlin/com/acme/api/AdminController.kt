package com.acme.api

import com.acme.api.dto.Role
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Second controller in the fixture so the integration test verifies that
 * `Role` (referenced by both AdminController via @RequestParam and —
 * transitively via UserDto — UserController) lands in `types.ws`, while
 * `UserDto` (referenced only by UserController) moves into
 * `UserController.ws`.
 *
 * Note: @RequestParam is used here to exercise query-parameter type binding;
 * both path and query parameters preserve the real binding type in the
 * Wirespec AST.
 */
@RestController
@RequestMapping("/admins")
class AdminController {

    @GetMapping("/by-role")
    fun listByRole(@RequestParam role: Role): List<String> = throw NotImplementedError()

    @GetMapping("/page")
    fun adminPage(): com.acme.api.dto.Page<com.acme.api.dto.UserDto> = throw NotImplementedError()
}
