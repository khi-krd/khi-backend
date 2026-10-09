package ak.dev.khi_backend.khi_app.api.project;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.project.ProjectCreateRequest;
import ak.dev.khi_backend.khi_app.dto.project.ProjectResponse;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.service.project.ProjectService;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * ProjectController — Tiptap-aware project endpoints.
 *
 * All endpoints are now plain {@code application/json}. The frontend uploads
 * the cover image and any inline media first via
 * {@code POST /api/v1/media/upload}, then sends URLs in the JSON body.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Bilingual projects with content blocks, tags, keywords, audit logs")
public class ProjectController {

    private final ProjectService projectService;


    private final SiteContentService siteContentService;

    // Featured Patch
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody(required = false) SiteContentDtos.FeaturedRequest request) {
        siteContentService.setProjectFeatured(id, request);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // CREATE
    // ============================================================
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ProjectResponse>> create(
            @Valid @RequestBody(required = false) ProjectCreateRequest request
    ) {
        log.info("POST /api/v1/projects/create | langs={}", request.getContentLanguages());

        ProjectResponse project = projectService.createResponse(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(project, "Project created successfully"));
    }

    // ============================================================
    // UPDATE
    // ============================================================
    @PutMapping(value = "/update/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ProjectResponse>> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) ProjectCreateRequest request
    ) {
        log.info("PUT /api/v1/projects/update/{} | langs={}", id, request.getContentLanguages());

        ProjectResponse project = projectService.updateResponse(id, request);

        return ResponseEntity.ok(
                ApiResponse.success(project, "Project updated successfully")
        );
    }

    // ============================================================
    // DELETE
    // ============================================================
    @DeleteMapping(value = "/delete/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        log.info("DELETE /api/v1/projects/delete/{}", id);

        projectService.delete(id);

        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // GET ALL
    // ============================================================
    @GetMapping(value = "/getAll", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<ProjectResponse>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/projects/getAll | page={} size={}", page, size);

        Page<ProjectResponse> result = projectService.getAllResponse(page, size);

        return ResponseEntity.ok(
                ApiResponse.success(result, "Projects fetched successfully")
        );
    }

    @GetMapping(value = "/featured", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<ProjectResponse>>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.getFeatured(page, size),
                "Featured projects fetched successfully"));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ProjectResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.getByIdResponse(id), "Project fetched successfully"));
    }

    // ============================================================
    // SEARCH
    // ============================================================
    @GetMapping(value = "/search/tag", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<ProjectResponse>>> searchByTag(
            @RequestParam("tag") String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/projects/search/tag | tag={}", tag);

        Page<ProjectResponse> result = projectService.searchByTagResponse(tag, page, size);

        return ResponseEntity.ok(
                ApiResponse.success(result, "Search by tag completed")
        );
    }

    @GetMapping(value = "/search/keyword", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<ProjectResponse>>> searchByKeyword(
            @RequestParam("keyword") String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/projects/search/keyword | keyword={}", keyword);

        Page<ProjectResponse> result = projectService.searchByKeywordResponse(keyword, page, size);

        return ResponseEntity.ok(
                ApiResponse.success(result, "Search by keyword completed")
        );
    }
}
