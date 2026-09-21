package ak.dev.khi_backend.khi_app.api.publishment.image;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.ReorderRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO.*;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.service.publishment.image.ImageCollectionService;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/image-collections")
@RequiredArgsConstructor
@Tag(name = "Image Collections", description = "Bilingual image collections with album items and topic taxonomy")
public class ImageCollectionController {

    private final ImageCollectionService   imageCollectionService;
    private final PublishmentTopicRepository topicRepository;
    private final ObjectMapper             objectMapper;

    private final SiteContentService siteContentService;

    // Featured Patch
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody SiteContentDtos.FeaturedRequest request) {
        siteContentService.setImageCollectionFeatured(id, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk list-order update — JSON body: { "orderedIds": [3, 1, 2, …] }.
     * orderedIds[i] gets sortOrder = i. Literal path wins over PUT /{id}
     * (which consumes multipart only).
     */
    @PutMapping(value = "/order", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Void>> reorder(
            @RequestBody ReorderRequest request) {
        imageCollectionService.reorder(request != null ? request.getOrderedIds() : null);
        return ResponseEntity.ok(ApiResponse.success(null, "Order updated"));
    }

    // =========================================================================
    // CREATE — multipart/form-data
    //
    //  FormData fields:
    //    data            (required) — JSON string: CreateRequest
    //    ckbCoverImage   (optional) — Sorani   cover image file
    //    kmrCoverImage   (optional) — Kurmanji cover image file
    //    hoverCoverImage (optional) — hover overlay image file
    //    images          (optional) — album image files (one or many)
    // =========================================================================

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Response>> create(
            @RequestPart("data")                                  String            dataJson,
            @RequestPart(value = "ckbCoverImage",   required = false) MultipartFile ckbCoverImage,
            @RequestPart(value = "kmrCoverImage",   required = false) MultipartFile kmrCoverImage,
            @RequestPart(value = "hoverCoverImage", required = false) MultipartFile hoverCoverImage,
            @RequestPart(value = "images",          required = false) List<MultipartFile> images
    ) throws Exception {

        CreateRequest dto = objectMapper.readValue(dataJson, CreateRequest.class);

        log.info("POST /api/v1/image-collections | type={} ckbCover={} kmrCover={} hoverCover={} " +
                        "imageFiles={} albumDtoItems={} langs={}",
                dto.getCollectionType(),
                hasFile(ckbCoverImage), hasFile(kmrCoverImage), hasFile(hoverCoverImage),
                countFiles(images),
                dto.getImageAlbum() != null ? dto.getImageAlbum().size() : 0,
                dto.getContentLanguages());

        Response created = imageCollectionService.create(
                dto, ckbCoverImage, kmrCoverImage, hoverCoverImage, images);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Image collection created successfully"));
    }

    // =========================================================================
    // CREATE — application/json (URL-only sources, no file uploads)
    // =========================================================================

    @PostMapping(
            value    = "/json",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Response>> createJson(
            @Valid @RequestBody CreateRequest dto
    ) {
        log.info("POST /api/v1/image-collections/json | type={} albumDtoItems={} langs={}",
                dto.getCollectionType(),
                dto.getImageAlbum() != null ? dto.getImageAlbum().size() : 0,
                dto.getContentLanguages());

        Response created = imageCollectionService.create(dto, null, null, null, null);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Image collection created successfully"));
    }

    // =========================================================================
    // UPDATE — multipart/form-data
    //
    //  Only covers / fields present in the request are changed.
    //  To remove a topic send clearTopic=true inside the data JSON.
    // =========================================================================

    @PutMapping(
            value    = "/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Response>> update(
            @PathVariable Long id,
            @RequestPart("data")                                  String            dataJson,
            @RequestPart(value = "ckbCoverImage",   required = false) MultipartFile ckbCoverImage,
            @RequestPart(value = "kmrCoverImage",   required = false) MultipartFile kmrCoverImage,
            @RequestPart(value = "hoverCoverImage", required = false) MultipartFile hoverCoverImage,
            @RequestPart(value = "images",          required = false) List<MultipartFile> images
    ) throws Exception {

        UpdateRequest dto = objectMapper.readValue(dataJson, UpdateRequest.class);

        log.info("PUT /api/v1/image-collections/{} | type={} ckbCover={} kmrCover={} hoverCover={} " +
                        "imageFiles={} albumDtoItems={} clearTopic={}",
                id, dto.getCollectionType(),
                hasFile(ckbCoverImage), hasFile(kmrCoverImage), hasFile(hoverCoverImage),
                countFiles(images),
                dto.getImageAlbum() != null ? dto.getImageAlbum().size() : -1,
                dto.isClearTopic());

        Response updated = imageCollectionService.update(
                id, dto, ckbCoverImage, kmrCoverImage, hoverCoverImage, images);

        return ResponseEntity.ok(ApiResponse.success(updated, "Image collection updated successfully"));
    }

    // =========================================================================
    // GET ALL
    // =========================================================================

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getAll(
            @RequestParam(required = false) ImageCollectionType type,
            @RequestParam(required = false) Long topicId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<Response> result = type != null
                ? imageCollectionService.getByType(type, page, size)
                : topicId != null
                    ? imageCollectionService.getByTopic(topicId, page, size)
                    : imageCollectionService.getAll(page, size);
        log.info("GET /api/v1/image-collections | type={} topicId={} page={} size={}",
                type, topicId, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                result,
                "Image collections fetched successfully"));
    }

    @GetMapping(value = "/featured", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                imageCollectionService.getFeatured(page, size),
                "Featured image collections fetched successfully"));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Response>> getById(@PathVariable Long id) {
        log.info("GET /api/v1/image-collections/{}", id);
        return ResponseEntity.ok(ApiResponse.success(
                imageCollectionService.getById(id),
                "Image collection fetched successfully"));
    }

    @GetMapping(value = "/slug/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Response>> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.success(
                imageCollectionService.getBySlug(slug),
                "Image collection fetched successfully"));
    }
    // =========================================================================
    // DELETE
    // =========================================================================

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("DELETE /api/v1/image-collections/{}", id);
        imageCollectionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // TOPICS — list IMAGE topics for frontend autocomplete
    //
    //  GET /api/v1/image-collections/topics
    //  Returns all PublishmentTopic records with entityType = "IMAGE".
    //  Mirrors the endpoint on SoundTrackController.
    // =========================================================================

    @GetMapping(value = "/topics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopics() {
        List<PublishmentTopic> topics = topicRepository.findByEntityType("IMAGE");
        List<Map<String, Object>> result = topics.stream()
                .map(t -> Map.<String, Object>of(
                        "id",      t.getId(),
                        "nameCkb", t.getNameCkb() != null ? t.getNameCkb() : "",
                        "nameKmr", t.getNameKmr() != null ? t.getNameKmr() : ""
                ))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result, "IMAGE topics fetched successfully"));
    }

    // =========================================================================
    // PRIVATE — utils
    // =========================================================================

    private boolean hasFile(MultipartFile f) { return f != null && !f.isEmpty(); }

    private int countFiles(List<MultipartFile> files) {
        if (files == null) return 0;
        return (int) files.stream().filter(f -> f != null && !f.isEmpty()).count();
    }
}
