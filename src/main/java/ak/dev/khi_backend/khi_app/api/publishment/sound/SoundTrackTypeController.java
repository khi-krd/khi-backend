package ak.dev.khi_backend.khi_app.api.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackTypeDtos.SoundTrackTypeRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackTypeDtos.SoundTrackTypeResponse;
import ak.dev.khi_backend.khi_app.service.publishment.sound.SoundTrackTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sound-type vocabulary — the options offered by the dashboard type picker.
 * Public reads, employee+ writes. Renames are propagated onto the free-text
 * {@code soundType} column of matching tracks; deletes only remove the
 * suggestion.
 */
@RestController
@RequestMapping("/api/v1/sound-tracks/types")
@RequiredArgsConstructor
@Tag(name = "Sound Track Types", description = "Sound-type CRUD; public reads, admin writes")
public class SoundTrackTypeController {

    private final SoundTrackTypeService soundTrackTypeService;

    @GetMapping
    public ApiResponse<List<SoundTrackTypeResponse>> getTypes() {
        return ApiResponse.success(soundTrackTypeService.list(), "Sound types fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SoundTrackTypeResponse> createType(
            @RequestBody(required = false) SoundTrackTypeRequest request) {
        return ApiResponse.success(soundTrackTypeService.create(request), "Sound type created");
    }

    @PutMapping("/{id}")
    public ApiResponse<SoundTrackTypeResponse> updateType(
            @PathVariable Long id,
            @RequestBody(required = false) SoundTrackTypeRequest request) {
        return ApiResponse.success(soundTrackTypeService.update(id, request), "Sound type updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteType(@PathVariable Long id) {
        soundTrackTypeService.delete(id);
        return ApiResponse.success(null, "Sound type deleted");
    }
}
