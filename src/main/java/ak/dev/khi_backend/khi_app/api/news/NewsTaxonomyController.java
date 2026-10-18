package ak.dev.khi_backend.khi_app.api.news;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.CategoryRequest;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.CategoryResponse;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.SubCategoryRequest;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.SubCategoryResponse;
import ak.dev.khi_backend.khi_app.service.news.NewsTaxonomyService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * News taxonomy vocabulary — categories each carrying their sub-categories.
 * Public reads (the dashboard pickers draw whatever this returns), employee+
 * writes. Deletes reassign orphaned news to a fallback bucket so the
 * required category/subCategory foreign keys never break.
 */
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
@Tag(name = "News Taxonomy", description = "News category & sub-category CRUD; public reads, admin writes")
public class NewsTaxonomyController {

    private final NewsTaxonomyService taxonomyService;

    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> getCategories() {
        return ApiResponse.success(taxonomyService.list(), "News categories fetched");
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CategoryResponse> createCategory(@RequestBody(required = false) CategoryRequest request) {
        return ApiResponse.success(taxonomyService.createCategory(request), "News category created");
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<CategoryResponse> updateCategory(
            @PathVariable Long id, @RequestBody(required = false) CategoryRequest request) {
        return ApiResponse.success(taxonomyService.updateCategory(id, request), "News category updated");
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        taxonomyService.deleteCategory(id);
        return ApiResponse.success(null, "News category deleted");
    }

    @PostMapping("/subcategories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubCategoryResponse> createSubCategory(@RequestBody(required = false) SubCategoryRequest request) {
        return ApiResponse.success(taxonomyService.createSubCategory(request), "News sub-category created");
    }

    @PutMapping("/subcategories/{id}")
    public ApiResponse<SubCategoryResponse> updateSubCategory(
            @PathVariable Long id, @RequestBody(required = false) SubCategoryRequest request) {
        return ApiResponse.success(taxonomyService.updateSubCategory(id, request), "News sub-category updated");
    }

    @DeleteMapping("/subcategories/{id}")
    public ApiResponse<Void> deleteSubCategory(@PathVariable Long id) {
        taxonomyService.deleteSubCategory(id);
        return ApiResponse.success(null, "News sub-category deleted");
    }
}
