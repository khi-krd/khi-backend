package ak.dev.khi_backend.khi_app.api.service;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.service.ServiceDTOs.*;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.service.service.ServiceService;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ServiceController — REST endpoints for the Service module.
 *
 * <p>Service carries no standalone media field — all media (image / video /
 * voice / document / other) is embedded inside the bilingual Tiptap
 * {@code description} on each content row. The frontend uploads each file
 * once via the shared {@code POST /api/v1/media/upload} and bakes the
 * returned URL into the editor before submitting the JSON body here.</p>
 *
 * ─── Base Path ────────────────────────────────────────────────────────────────
 *  /api/v1/services
 *
 * ─── Endpoints ────────────────────────────────────────────────────────────────
 *  GET    /api/v1/services                                → active services (paginated)
 *  GET    /api/v1/services?type={serviceType}             → filter active by type (paginated)
 *  GET    /api/v1/services/all                            → admin: all incl. inactive (paginated)
 *  GET    /api/v1/services/{id}                           → single service (full detail)
 *  GET    /api/v1/services/types                          → distinct service type names
 *  GET    /api/v1/services/search?q={query}               → global search (active, paginated)
 *  GET    /api/v1/services/search/admin?q={query}         → admin search (all, paginated)
 *  POST   /api/v1/services                                → create service (JSON)
 *  PUT    /api/v1/services/{id}                           → full update (JSON)
 *  PATCH  /api/v1/services/{id}/active?value=false        → soft toggle
 *  PATCH  /api/v1/services/{id}/featured                  → admin: feature / unfeature
 *  GET    /api/v1/services/featured                       → featured services (paginated)
 *  DELETE /api/v1/services/{id}                           → delete
 *  DELETE /api/v1/services/bulk                           → bulk delete
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/services")
@RequiredArgsConstructor
@Tag(name = "Services", description = "Bilingual service catalogue with soft-active and type filtering")
public class ServiceController {

    private final ServiceService serviceService;
    private final SiteContentService siteContentService;

    // =========================================================================
    // FEATURED (homepage carousel)
    // =========================================================================

    /**
     * Feature / unfeature a service.
     *
     * <p>Featuring highlights the service in the band at the top of the public
     * Services page — it is not a homepage carousel slide and takes no share of
     * the hero slide cap, so any number of services may be featured.</p>
     *
     * <p>{@code featureImageUrl} is optional when the service already has a
     * gallery image; the card falls back to the first gallery picture (or a video
     * slot's poster). Omitting the field leaves the stored value alone; sending
     * {@code ""} clears it.</p>
     *
     * <p>Ordinary page content, so SUPER_ADMIN may write it too — unlike the six
     * carousel toggles, which stay ADMIN-only.</p>
     */
    @Operation(summary = "Feature / unfeature a service on the Services page (ADMIN, SUPER_ADMIN)")
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody SiteContentDtos.FeaturedRequest request) {
        siteContentService.setServiceFeatured(id, request);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // SERVICE CRUD
    // =========================================================================

    /**
     * List all active services — paginated.
     * Optional ?type=Training filter narrows results to one service type.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ServiceResponse>>> getAll(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ServiceResponse> result = (type != null && !type.isBlank())
                ? serviceService.getAllActiveByType(type, page, size)
                : serviceService.getAllActive(page, size);

        return ResponseEntity.ok(
                ApiResponse.success(result, "Services fetched successfully"));
    }

    @GetMapping("/all")
    public ResponseEntity<ApiResponse<Page<ServiceResponse>>> getAllPublic(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ServiceResponse> result = serviceService.getAllActive(page, size);
        return ResponseEntity.ok(
                ApiResponse.success(result, "Services fetched successfully"));
    }

    @GetMapping("/admin/all")
    public ResponseEntity<ApiResponse<Page<ServiceResponse>>> getAllAdmin(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                serviceService.getAll(page, size), "All services fetched successfully"));
    }

    @GetMapping("/featured")
    public ResponseEntity<ApiResponse<Page<ServiceResponse>>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                serviceService.getFeatured(page, size),
                "Featured services fetched successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.success(serviceService.getById(id), "Service fetched successfully"));
    }

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<String>>> getServiceTypes() {
        return ResponseEntity.ok(
                ApiResponse.success(serviceService.getServiceTypes(), "Service types fetched"));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Page<ServiceResponse>>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                ApiResponse.success(serviceService.globalSearch(q, page, size),
                        "Search results fetched"));
    }

    @GetMapping("/search/admin")
    public ResponseEntity<ApiResponse<Page<ServiceResponse>>> adminSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                ApiResponse.success(serviceService.adminSearch(q, page, size),
                        "Admin search results fetched"));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ServiceResponse>> create(
            @RequestBody ServiceRequest request) {

        ServiceResponse response = serviceService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Service created successfully"));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ServiceResponse>> update(
            @PathVariable Long id,
            @RequestBody ServiceRequest request) {

        return ResponseEntity.ok(
                ApiResponse.success(serviceService.update(id, request),
                        "Service updated successfully"));
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<ApiResponse<ServiceResponse>> setActive(
            @PathVariable Long id,
            @RequestParam boolean value) {

        return ResponseEntity.ok(
                ApiResponse.success(serviceService.setActive(id, value),
                        value ? "Service activated" : "Service deactivated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        serviceService.delete(id);
        return ResponseEntity.ok(
                ApiResponse.success(null, "Service deleted successfully"));
    }

    @DeleteMapping("/bulk")
    public ResponseEntity<ApiResponse<Void>> deleteBulk(@RequestBody List<Long> ids) {
        serviceService.deleteBulk(ids);
        return ResponseEntity.ok(
                ApiResponse.success(null, "Services deleted successfully"));
    }
}
