package ak.dev.khi_backend.khi_app.api.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionResponse;
import ak.dev.khi_backend.khi_app.service.publishment.writing.BookLanguageService;
import ak.dev.khi_backend.khi_app.service.publishment.writing.EditionService;
import ak.dev.khi_backend.khi_app.service.publishment.writing.ManagedOptionService;
import ak.dev.khi_backend.khi_app.service.publishment.writing.WritingTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Editor-managed writing vocabularies — writing types, editions and book
 * languages. Identical shape to /api/v1/book-genres: public reads (the
 * website and pickers draw whatever this returns, in displayOrder), admin
 * writes enforced in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Writing Options", description = "Editor-managed writing types, editions and book languages; public reads, admin CRUD")
public class WritingOptionsController {

    private final WritingTypeService   writingTypeService;
    private final EditionService       editionService;
    private final BookLanguageService  bookLanguageService;

    // ─── Writing Types (جۆری نووسین) ──────────────────────────────────────────

    @GetMapping("/writing-types")
    public ApiResponse<List<ManagedOptionResponse>> getWritingTypes(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(writingTypeService.list(includeInactive), "Writing types fetched");
    }

    @PostMapping("/writing-types")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedOptionResponse> createWritingType(
            @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(writingTypeService.create(request), "Writing type created");
    }

    @PutMapping("/writing-types/{id}")
    public ApiResponse<ManagedOptionResponse> updateWritingType(
            @PathVariable Long id, @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(writingTypeService.update(id, request), "Writing type updated");
    }

    @DeleteMapping("/writing-types/{id}")
    public ApiResponse<Void> deleteWritingType(@PathVariable Long id) {
        writingTypeService.delete(id);
        return ApiResponse.success(null, "Writing type deleted");
    }

    // ─── Editions (چاپ) ───────────────────────────────────────────────────────

    @GetMapping("/editions")
    public ApiResponse<List<ManagedOptionResponse>> getEditions(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(editionService.list(includeInactive), "Editions fetched");
    }

    @PostMapping("/editions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedOptionResponse> createEdition(
            @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(editionService.create(request), "Edition created");
    }

    @PutMapping("/editions/{id}")
    public ApiResponse<ManagedOptionResponse> updateEdition(
            @PathVariable Long id, @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(editionService.update(id, request), "Edition updated");
    }

    @DeleteMapping("/editions/{id}")
    public ApiResponse<Void> deleteEdition(@PathVariable Long id) {
        editionService.delete(id);
        return ApiResponse.success(null, "Edition deleted");
    }

    // ─── Book Languages (زمانی کتێب) ──────────────────────────────────────────

    @GetMapping("/book-languages")
    public ApiResponse<List<ManagedOptionResponse>> getBookLanguages(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(bookLanguageService.list(includeInactive), "Book languages fetched");
    }

    @PostMapping("/book-languages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedOptionResponse> createBookLanguage(
            @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(bookLanguageService.create(request), "Book language created");
    }

    @PutMapping("/book-languages/{id}")
    public ApiResponse<ManagedOptionResponse> updateBookLanguage(
            @PathVariable Long id, @Valid @RequestBody(required = false) ManagedOptionRequest request) {
        return ApiResponse.success(bookLanguageService.update(id, request), "Book language updated");
    }

    @DeleteMapping("/book-languages/{id}")
    public ApiResponse<Void> deleteBookLanguage(@PathVariable Long id) {
        bookLanguageService.delete(id);
        return ApiResponse.success(null, "Book language deleted");
    }
}
