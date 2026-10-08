package ak.dev.khi_backend.khi_app.api.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
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
import java.util.List;
import java.util.Map;

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

    private final WritingService writingService;
    private final ObjectMapper objectMapper;
    private final PublishmentTopicRepository topicRepository;

    private final SiteContentService siteContentService;

    // Featured Patch
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody SiteContentDtos.FeaturedRequest request) {
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
            @RequestPart(value = "data") String dataJson,
            @RequestPart(value = "ckbCoverImage", required = false) MultipartFile ckbCoverImage,
            @RequestPart(value = "kmrCoverImage", required = false) MultipartFile kmrCoverImage,
            @RequestPart(value = "hoverCoverImage", required = false) MultipartFile hoverCoverImage,
            @RequestPart(value = "ckbBookFile", required = false) MultipartFile ckbBookFile,
            @RequestPart(value = "kmrBookFile", required = false) MultipartFile kmrBookFile) throws IOException {

        CreateRequest request = parseJson(dataJson, CreateRequest.class);

        int fileCount = countFiles(ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile);
        log.info("POST /api/v1/writings | langs={} | genres={} | files={}",
                request.getContentLanguages(), request.getBookGenres(), fileCount);

        Response response = writingService.addWriting(
                request, ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile
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
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /api/v1/writings | page={} size={}", page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Response> result = writingService.getAllWritings(pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Writings fetched successfully"));
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
     * Update writing with files (multipart).
     * Replaces entire bookGenres set with new array from request.
     */
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Response>> update(
            @PathVariable Long id,
            @RequestPart(value = "data") String dataJson,
            @RequestPart(value = "ckbCoverImage", required = false) MultipartFile ckbCoverImage,
            @RequestPart(value = "kmrCoverImage", required = false) MultipartFile kmrCoverImage,
            @RequestPart(value = "hoverCoverImage", required = false) MultipartFile hoverCoverImage,
            @RequestPart(value = "ckbBookFile", required = false) MultipartFile ckbBookFile,
            @RequestPart(value = "kmrBookFile", required = false) MultipartFile kmrBookFile) throws IOException {

        UpdateRequest request = parseJson(dataJson, UpdateRequest.class);
        int fileCount = countFiles(ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile);
        log.info("PUT /api/v1/writings/{} | files={} | genres={}", id, fileCount, request.getBookGenres());

        Response response = writingService.updateWriting(
                id, request, ckbCoverImage, kmrCoverImage, hoverCoverImage, ckbBookFile, kmrBookFile
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
                writingService.getAllSeriesParents(pageable), "Series parents fetched"));
    }

    @PostMapping("/series/link")
    public ResponseEntity<ApiResponse<Response>> linkToSeries(
            @Valid @RequestBody LinkToSeriesRequest request) {
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
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
                writingService.searchByWriter(name, language, pageable), "Search completed"));
    }

    @GetMapping("/search/tag")
    public ResponseEntity<ApiResponse<Page<Response>>> searchByTag(
            @RequestParam String tag,
            @RequestParam(defaultValue = "both") String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
                writingService.searchByTag(tag, language, pageable), "Search completed"));
    }

    @GetMapping("/search/keyword")
    public ResponseEntity<ApiResponse<Page<Response>>> searchByKeyword(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "both") String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
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
            return objectMapper.readValue(json, clazz);
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
}
