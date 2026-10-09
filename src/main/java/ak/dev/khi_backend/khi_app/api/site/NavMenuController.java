package ak.dev.khi_backend.khi_app.api.site;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.site.NavMenuDtos.*;
import ak.dev.khi_backend.khi_app.service.site.NavMenuService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hamburger menu of the public website. Reads are public, the image update is
 * admin-only — see the {@code /api/v1/nav-menu/**} rules in {@code SecurityConfig}.
 * There is no create/delete: the section set is fixed by the website's nav config.
 */
@RestController
@RequestMapping("/api/v1/nav-menu")
@RequiredArgsConstructor
@Tag(name = "Nav Menu", description = "Website hamburger menu background images")
public class NavMenuController {

    private final NavMenuService service;

    /** @param includeInactive dashboard only — the website never sends it. */
    @GetMapping
    public ApiResponse<List<NavMenuItemResponse>> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(service.list(includeInactive), "Nav menu fetched");
    }

    @GetMapping("/{id}")
    public ApiResponse<NavMenuItemResponse> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id), "Nav menu item fetched");
    }

    /** Sets (or clears) the section's background photo; optionally flips {@code active}. */
    @PutMapping("/{id}")
    public ApiResponse<NavMenuItemResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody(required = false) NavMenuItemRequest request) {
        return ApiResponse.success(service.update(id, request), "Nav menu item updated");
    }
}
