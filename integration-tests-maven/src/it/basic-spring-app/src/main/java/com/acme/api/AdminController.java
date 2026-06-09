package com.acme.api;

import com.acme.api.dto.Role;
import java.util.Collections;
import java.util.List;
import org.springframework.web.bind.annotation.*;

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
public class AdminController {

    @GetMapping("/by-role")
    public List<String> listByRole(@RequestParam Role role) {
        return Collections.emptyList();
    }

    @GetMapping("/page")
    public com.acme.api.dto.Page<com.acme.api.dto.UserDto> adminPage() {
        throw new UnsupportedOperationException();
    }
}
