package ak.dev.khi_backend.user.api;

import ak.dev.khi_backend.user.dto.UserCreateRequestDTO;
import ak.dev.khi_backend.user.dto.UserResponseDTO;
import ak.dev.khi_backend.user.dto.UserUpdateRequestDTO;
import ak.dev.khi_backend.user.enums.Role;
import ak.dev.khi_backend.user.model.User;
import ak.dev.khi_backend.user.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin user management — SUPER_ADMIN only (SecurityConfig pins
 * {@code /api/users/**} to that role; this controller deliberately sits on
 * that prefix rather than the public {@code /api/auth} or self-service
 * {@code /api/user} namespaces).
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "Admin CRUD + role/status management")
public class UserManagementAPI {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<Page<UserResponseDTO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userService.searchUsers(keyword, role, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDTO> get(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponseDTO create(@Valid @RequestBody UserCreateRequestDTO dto) {
        return userService.createUser(dto, null);
    }

    @PutMapping("/{id}")
    public UserResponseDTO update(@PathVariable Long id,
                                  @Valid @RequestBody UserUpdateRequestDTO dto) {
        return userService.updateUser(id, dto, null);
    }

    @PatchMapping("/{id}/role")
    public UserResponseDTO changeRole(@PathVariable Long id,
                                      @RequestBody Map<String, String> body,
                                      @AuthenticationPrincipal UserDetails principal) {
        Role role = Role.valueOf(body.getOrDefault("role", "").toUpperCase());
        return userService.changeRole(id, role, actorId(principal));
    }

    @PatchMapping("/{id}/status")
    public UserResponseDTO setStatus(@PathVariable Long id,
                                     @RequestBody Map<String, Boolean> body,
                                     @AuthenticationPrincipal UserDetails principal) {
        boolean activated = Boolean.TRUE.equals(body.get("isActivated"));
        return userService.setActivated(id, activated, actorId(principal));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails principal) {
        userService.deleteUser(id, actorId(principal));
        return ResponseEntity.noContent().build();
    }

    private Long actorId(UserDetails principal) {
        return principal instanceof User u ? u.getUserId() : null;
    }
}
