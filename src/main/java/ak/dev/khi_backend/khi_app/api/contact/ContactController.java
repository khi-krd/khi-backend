package ak.dev.khi_backend.khi_app.api.contact;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.contact.ContactDTOs.*;
import ak.dev.khi_backend.khi_app.service.contact.ContactService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * REST Controller for Contact page management.
 *
 * Contact has no standalone media field — all visual media (image, video,
 * voice, document, or any other file) lives inside the bilingual Tiptap
 * {@code description}. The frontend uploads each file once via the shared
 * {@code POST /api/v1/media/upload}, bakes the returned URL into the editor,
 * then submits the JSON body here.
 */
@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Contact", description = "Bilingual Contact pages with Tiptap descriptions")
public class ContactController {

    private final ContactService contactService;

    // ============================================================
    // READ
    // ============================================================

    /** All contact pages (admin — includes inactive). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ContactResponse>>> getAll() {
        log.info("GET /api/v1/contact");
        return ResponseEntity.ok(ApiResponse.success(
                contactService.getAll(), "Contact pages fetched"));
    }

    /** All active contact pages (public). */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<Page<ContactResponse>>> getAllActive(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                contactService.getAllActive(page, size), "Active contact pages fetched"));
    }

    /** Get by ID. */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContactResponse>> getById(@PathVariable Long id) {
        log.info("GET /api/v1/contact/{}", id);
        return ResponseEntity.ok(ApiResponse.success(
                contactService.getById(id), "Contact page fetched"));
    }

    /**
     * Get by slug — works with either CKB or KMR slug.
     * Used by the public frontend for route-based navigation.
     */
    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<ContactResponse>> getBySlug(@PathVariable String slug) {
        log.info("GET /api/v1/contact/slug/{}", slug);
        return ResponseEntity.ok(ApiResponse.success(
                contactService.getBySlug(slug), "Contact page fetched"));
    }

    // ============================================================
    // CREATE
    // ============================================================

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ContactResponse>> create(
            @Valid @RequestBody(required = false) ContactRequest request) {

        log.info("POST /api/v1/contact | slugCkb={}", request.getSlugCkb());
        ContactResponse response = contactService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Contact page created successfully"));
    }

    // ============================================================
    // UPDATE
    // ============================================================

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ContactResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ContactRequest request) {

        log.info("PUT /api/v1/contact/{}", id);
        return ResponseEntity.ok(ApiResponse.success(
                contactService.update(id, request), "Contact page updated successfully"));
    }

    // ============================================================
    // DELETE
    // ============================================================

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        log.info("DELETE /api/v1/contact/{}", id);
        contactService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Contact page deleted successfully"));
    }
}
