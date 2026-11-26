package ak.dev.khi_backend.khi_app.api.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionResponse;
import ak.dev.khi_backend.khi_app.service.publishment.sound.SoundStateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Editor-managed sound types (جۆرەکانی دەنگ) — تاک, ئەلبووم and whatever
 * admins add later. Public reads, admin writes in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/sound-states")
@RequiredArgsConstructor
@Tag(name = "Sound States", description = "Editor-managed sound types; public reads, admin CRUD")
public class SoundStatesController {

    private final SoundStateService soundStateService;

    @GetMapping
    public ApiResponse<List<ManagedOptionResponse>> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(soundStateService.list(includeInactive), "Sound states fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedOptionResponse> create(
            @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(soundStateService.create(request), "Sound state created");
    }

    @PutMapping("/{id}")
    public ApiResponse<ManagedOptionResponse> update(
            @PathVariable Long id, @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(soundStateService.update(id, request), "Sound state updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        soundStateService.delete(id);
        return ApiResponse.success(null, "Sound state deleted");
    }
}
