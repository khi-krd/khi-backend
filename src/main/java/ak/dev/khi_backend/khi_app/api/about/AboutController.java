package ak.dev.khi_backend.khi_app.api.about;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.about.AboutDTOs;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.service.about.AboutService;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AboutController — Tiptap-aware About endpoints.
 *
 * About carries no standalone media field — all visual media (image, video,
 * voice, document, or any other file) lives inside {@code ckbContent.body}
 * and {@code kmrContent.body} as Tiptap HTML. The frontend uploads each
 * file once via the shared {@code POST /api/v1/media/upload}, bakes the
 * returned URL into the editor, then submits the JSON body to this
 * controller. {@link ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor}
 * also acts as a safety net that rewrites any inline base64 payloads on save.
 */
@RestController
@RequestMapping("/api/v1/about")
@RequiredArgsConstructor
@Tag(name = "About", description = "Bilingual About pages with Tiptap bodies and structured stats")
public class AboutController {

    private final AboutService aboutService;
    private final SiteContentService siteContentService;

    /**
     * Feature / unfeature an About page.
     *
     * <p>Featuring makes the record LEAD the public About page — it is not a
     * homepage carousel slide and takes no share of the hero slide cap.</p>
     *
     * <p>{@code featureImageUrl} must be present (either already stored or sent
     * in this request) before the page can be featured: it becomes the About page
     * hero image, and About owns no cover to fall back on. Omitting the field
     * leaves the stored value alone; sending {@code ""} clears it.</p>
     *
     * <p>Ordinary page content, so SUPER_ADMIN may write it too — unlike the six
     * carousel toggles, which stay ADMIN-only.</p>
     */
    @Operation(summary = "Feature / unfeature an About page on the About page (ADMIN, SUPER_ADMIN)")
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody SiteContentDtos.FeaturedRequest request) {
        siteContentService.setAboutFeatured(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<AboutDTOs.AboutResponse>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                aboutService.getAllActive(page, size), "About pages fetched"));
    }

    /**
     * Backward-compatible detail lookup. Numeric values resolve by ID; all
     * other values resolve against both localized slugs.
     */
    @GetMapping("/{identifier}")
    public ResponseEntity<ApiResponse<AboutDTOs.AboutResponse>> getByIdentifier(
            @PathVariable String identifier) {
        return ResponseEntity.ok(ApiResponse.success(
                aboutService.getByIdentifier(identifier), "About page fetched"));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<AboutDTOs.AboutResponse>> getBySlug(
            @PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.success(
                aboutService.getBySlug(slug), "About page fetched"));
    }

    @PostMapping
    public ResponseEntity<AboutDTOs.AboutResponse> create(
            @RequestBody AboutDTOs.AboutRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(aboutService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AboutDTOs.AboutResponse> update(
            @PathVariable Long id,
            @RequestBody AboutDTOs.AboutRequest request) {

        return ResponseEntity.ok(aboutService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        aboutService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
