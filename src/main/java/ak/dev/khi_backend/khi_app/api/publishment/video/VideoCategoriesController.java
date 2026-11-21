package ak.dev.khi_backend.khi_app.api.publishment.video;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionResponse;
import ak.dev.khi_backend.khi_app.service.publishment.video.VideoCategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Editor-managed video categories (پۆلەکانی ڤیدیۆ) — فیلم, کلیپ, بەرنامەی
 * فۆلکلۆری, چاوپێکەوتن, بەڵگەفیلم and whatever admins add later. Same shape
 * as the writings vocabularies: public reads, admin writes in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/video-categories")
@RequiredArgsConstructor
@Tag(name = "Video Categories", description = "Editor-managed video categories; public reads, admin CRUD")
public class VideoCategoriesController {

    private final VideoCategoryService videoCategoryService;

    @GetMapping
    public ApiResponse<List<ManagedOptionResponse>> getCategories(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(videoCategoryService.list(includeInactive), "Video categories fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedOptionResponse> createCategory(
            @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(videoCategoryService.create(request), "Video category created");
    }

    @PutMapping("/{id}")
    public ApiResponse<ManagedOptionResponse> updateCategory(
            @PathVariable Long id, @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(videoCategoryService.update(id, request), "Video category updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        videoCategoryService.delete(id);
        return ApiResponse.success(null, "Video category deleted");
    }
}
