package ak.dev.khi_backend.khi_app.api.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.ReorderRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.*;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.service.publishment.writing.WritingService;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller for Writing/Book management.
 * Supports multiple BookGenres (Set<BookGenre>) in Create/Update requests.
 */
@RestController
@RequestMapping("/api/v1/writings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Writings", description = "Bilingual books with multi-genre, series linking, multi-axis search")
public class WritingController {

    /**
     * Public/admin browse order: manual sortOrder first (ASC puts NULLs last
     * on Postgres), then the previous default (newest created).
     */
    private static final Sort DEFAULT_LIST_SORT =
            Sort.by(Sort.Direction.ASC, "sortOrder")
                    .and(Sort.by(Sort.Direction.DESC, "createdAt"));

    /**
     * Properties a caller may sort {@link #getAll} by — a whitelist so a bogus
     * {@code sort=foo,desc} can never reach JPQL. Newest-first is
     * {@code createdAt,desc} (and {@code id,desc} as a stable tiebreak).
     */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("createdAt", "updatedAt", "sortOrder", "id");

    private final WritingService writingService;
    private final ObjectMapper objectMapper;
    private final PublishmentTopicRepository topicRepository;

    private final SiteContentService siteContentService;

    // Featured Patch
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody(required = false) SiteContentDtos.FeaturedRequest request) {
        siteContentService.setWritingFeatured(id, request);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // CREATE
    // ============================================================

    /**
     * Create writing with files (multipart).
     * Accepts bookGenres as JSON array: ["NOVEL", "HISTORY"]
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Response>> create(
            @RequestPart(value = "data", required = false) String dataJson,
            @RequestPart(value = "ckbCoverImage", required = false) MultipartFile ckbCoverImage,
            @RequestPart(value = "kmrCoverImage", required = false) MultipartFile kmrCoverImage,
            @RequestPart(value = "hoverCoverImage", required = false) MultipartFile hoverCoverImage,
            @RequestPart(value = "ckbBookFile", required = false) MultipartFile ckbBookFile,
            @RequestPart(value = "kmrBookFile", required = false) MultipartFile kmrBookFile,
            @RequestPart(value = "audioFile", required = false) MultipartFile audioFile,
            @RequestPart(value = "cdCoverImage", required = false) MultipartFile cdCoverImage) throws IOException {

        CreateRequest request = parseJson(dataJson, CreateRequest.class);

        int fileCount = countFiles(ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile,
                audioFile, cdCoverImage);
        log.info("POST /api/v1/writings | langs={} | genres={} | files={}",
                request.getContentLanguages(), request.getBookGenres(), fileCount);

        Response response = writingService.addWriting(
                request, ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile,
                audioFile, cdCoverImage
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Writing created successfully"));
    }

    // ============================================================
    // READ
    // ============================================================

    @GetMapping
    public ResponseEntity<ApiResponse<Page<Response>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> sort) {

        log.info("GET /api/v1/writings | page={} size={} sort={}", page, size, sort);
        Pageable pageable = PageRequest.of(page, size, resolveListSort(sort));
        Page<Response> result = writingService.getAllWritings(pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Writings fetched successfully"));
    }

    /**
     * Parses {@code sort=prop,dir} params (repeatable) against
     * {@link #SORTABLE_PROPERTIES}; unknown properties and any direction other
     * than asc/desc are dropped, and an empty result falls back to
     * {@link #DEFAULT_LIST_SORT}. The dashboard passes
     * {@code sort=createdAt,desc} to browse newest-first; the public default
     * keeps the manual {@code sortOrder} sequence.
     */
    private static Sort resolveListSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) return DEFAULT_LIST_SORT;
        List<Sort.Order> orders = new ArrayList<>();
        for (String entry : sort) {
            if (entry == null || entry.isBlank()) continue;
            String[] parts = entry.split(",", -1);
            String property = parts[0].trim();
            String canonical = SORTABLE_PROPERTIES.stream()
                    .filter(p -> p.equalsIgnoreCase(property))
                    .findFirst().orElse(null);
            if (canonical == null) continue;
            Sort.Direction direction = parts.length > 1
                    && parts[1].trim().equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            orders.add(new Sort.Order(direction, canonical));
        }
        return orders.isEmpty() ? DEFAULT_LIST_SORT : Sort.by(orders);
    }

    @GetMapping("/featured")
    public ResponseEntity<ApiResponse<Page<Response>>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                writingService.getFeatured(page, size),
                "Featured writings fetched successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Response>> getById(@PathVariable Long id) {
        log.info("GET /api/v1/writings/{}", id);
        Response response = writingService.getWritingById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Writing fetched successfully"));
    }

    // ============================================================
    // UPDATE
    // ============================================================

    /**
     * Bulk list-order update — JSON body: { "orderedIds": [3, 1, 2, …] }.
     * orderedIds[i] gets sortOrder = i. Literal path wins over PUT /{id}
     * (which consumes multipart only).
     */
    @PutMapping(value = "/order", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Void>> reorder(
            @RequestBody(required = false) ReorderRequest request) {
        writingService.reorder(request != null ? request.getOrderedIds() : null);
        return ResponseEntity.ok(ApiResponse.success(null, "Order updated"));
    }

    /**
     * Update writing with files (multipart).
     * Replaces entire bookGenres set with new array from request.
     */
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Response>> update(
            @PathVariable Long id,
            @RequestPart(value = "data", required = false) String dataJson,
            @RequestPart(value = "ckbCoverImage", required = false) MultipartFile ckbCoverImage,
            @RequestPart(value = "kmrCoverImage", required = false) MultipartFile kmrCoverImage,
            @RequestPart(value = "hoverCoverImage", required = false) MultipartFile hoverCoverImage,
            @RequestPart(value = "ckbBookFile", required = false) MultipartFile ckbBookFile,
            @RequestPart(value = "kmrBookFile", required = false) MultipartFile kmrBookFile,
            @RequestPart(value = "audioFile", required = false) MultipartFile audioFile,
            @RequestPart(value = "cdCoverImage", required = false) MultipartFile cdCoverImage) throws IOException {

        UpdateRequest request = parseJson(dataJson, UpdateRequest.class);
        int fileCount = countFiles(ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile,
                audioFile, cdCoverImage);
        log.info("PUT /api/v1/writings/{} | files={} | genres={}", id, fileCount, request.getBookGenres());

        Response response = writingService.updateWriting(
                id, request, ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile,
                audioFile, cdCoverImage
        );

        return ResponseEntity.ok(ApiResponse.success(response, "Writing updated successfully"));
    }

    // ============================================================
    // DELETE
    // ============================================================

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("DELETE /api/v1/writings/{}", id);
        writingService.deleteWriting(id);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // SERIES MANAGEMENT
    // ============================================================

    @GetMapping("/series/parents")
    public ResponseEntity<ApiResponse<Page<Response>>> getAllSeriesParents(
            @RequestParam(required = false) String title,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        Pageable pageable = PageRequest.of(page, size, DEFAULT_LIST_SORT);
        return ResponseEntity.ok(ApiResponse.success(
                writingService.getAllSeriesParents(title, pageable), "Series parents fetched"));
    }

    @PostMapping("/series/link")
    public ResponseEntity<ApiResponse<Response>> linkToSeries(
            @Valid @RequestBody(required = false) LinkToSeriesRequest request) {
        log.info("POST /series/link | book={} parent={}", request.getBookId(), request.getParentBookId());
        return ResponseEntity.ok(ApiResponse.success(
                writingService.linkBookToSeries(request), "Book linked to series"));
    }

    @GetMapping("/series/{seriesId}")
    public ResponseEntity<ApiResponse<SeriesResponse>> getSeriesBooks(
            @PathVariable String seriesId) {
        return ResponseEntity.ok(ApiResponse.success(
                writingService.getSeriesBooks(seriesId), "Series books fetched"));
    }

    // ============================================================
    // SEARCH
    // ============================================================

    @GetMapping("/search/writer")
    public ResponseEntity<ApiResponse<Page<Response>>> searchByWriter(
            @RequestParam String name,
            @RequestParam(defaultValue = "both") String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, DEFAULT_LIST_SORT);
        return ResponseEntity.ok(ApiResponse.success(
                writingService.searchByWriter(name, language, pageable), "Search completed"));
    }

    @GetMapping("/search/title")
    public ResponseEntity<ApiResponse<Page<Response>>> searchByTitle(
            @RequestParam String title,
            @RequestParam(defaultValue = "both") String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, DEFAULT_LIST_SORT);
        return ResponseEntity.ok(ApiResponse.success(
                writingService.searchByTitle(title, language, pageable), "Search completed"));
    }

    @GetMapping("/search/tag")
    public ResponseEntity<ApiResponse<Page<Response>>> searchByTag(
            @RequestParam String tag,
            @RequestParam(defaultValue = "both") String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, DEFAULT_LIST_SORT);
        return ResponseEntity.ok(ApiResponse.success(
                writingService.searchByTag(tag, language, pageable), "Search completed"));
    }

    @GetMapping("/search/keyword")
    public ResponseEntity<ApiResponse<Page<Response>>> searchByKeyword(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "both") String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, DEFAULT_LIST_SORT);
        return ResponseEntity.ok(ApiResponse.success(
                writingService.searchByKeyword(keyword, language, pageable), "Search completed"));
    }

    @GetMapping(value = "/topics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopics() {
        List<PublishmentTopic> topics = topicRepository.findByEntityType("WRITING");
        List<Map<String, Object>> result = topics.stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "nameCkb", t.getNameCkb() != null ? t.getNameCkb() : "",
                        "nameKmr", t.getNameKmr() != null ? t.getNameKmr() : ""
                ))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result, "WRITING topics fetched"));
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private <T> T parseJson(String json, Class<T> clazz) {
        try {
            return isBlank(json) ? null : objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("JSON parse error: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }

    private int countFiles(MultipartFile... files) {
        int count = 0;
        for (MultipartFile f : files) if (f != null && !f.isEmpty()) count++;
        return count;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
