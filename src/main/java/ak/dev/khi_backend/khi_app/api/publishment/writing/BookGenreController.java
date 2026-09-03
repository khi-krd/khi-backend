package ak.dev.khi_backend.khi_app.api.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.BookGenreRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.BookGenreResponse;
import ak.dev.khi_backend.khi_app.service.publishment.writing.BookGenreService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Editor-managed book genres — the chips on the writings page. Public reads
 * (the website draws whatever this returns, in displayOrder), admin writes.
 * Same shape as /api/v1/settings/social.
 */
@RestController
@RequestMapping("/api/v1/book-genres")
@RequiredArgsConstructor
@Tag(name = "Book Genres", description = "Editor-managed genre chips for writings; public reads, admin CRUD")
public class BookGenreController {

    private final BookGenreService bookGenreService;

    /** @param includeInactive dashboard only — the website never sends it. */
    @GetMapping
    public ApiResponse<List<BookGenreResponse>> getGenres(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(bookGenreService.getGenres(includeInactive),
                "Book genres fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BookGenreResponse> createGenre(
            @Valid @RequestBody BookGenreRequest request) {
        return ApiResponse.success(bookGenreService.createGenre(request), "Book genre created");
    }

    @PutMapping("/{id}")
    public ApiResponse<BookGenreResponse> updateGenre(
            @PathVariable Long id, @Valid @RequestBody BookGenreRequest request) {
        return ApiResponse.success(bookGenreService.updateGenre(id, request), "Book genre updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteGenre(@PathVariable Long id) {
        bookGenreService.deleteGenre(id);
        return ApiResponse.success(null, "Book genre deleted");
    }
}
