package ak.dev.khi_backend.khi_app.api.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.ReorderRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos.*;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.service.publishment.sound.SoundTrackService;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * SoundTrackController
 *
 * Base URL : /api/v1/sound-tracks
 *
 * ─── Write endpoints (multipart/form-data) ────────────────────────────────────
 *
 *  POST  /          → create
 *  PUT   /{id}      → update
 *
 *  FormData parts:
 *
 *   data              (required) — JSON string: CreateRequest / UpdateRequest
 *   ckbCoverImage     (optional) — Sorani cover image file
 *   kmrCoverImage     (optional) — Kurmanji cover image file
 *   hoverCoverImage   (optional) — hover-state cover image file
 *   audioFiles        (optional) — audio file binaries, index-matched to dto.files[i]
 *   brochureFiles     (optional) — brochure image binaries, flat list shared across files
 *   attachmentFiles   (optional) — attachment binaries (MULTI only), index-matched to dto.attachments[i]
 *
 * ─── Read endpoints (GET) ─────────────────────────────────────────────────────
 *
 *  GET   /                   → getAll                 ?page=0&size=20
 *  GET   /{id}               → getById
 *  DELETE/{id}               → delete
 *  GET   /by-state           → by SINGLE / MULTI      ?state=SINGLE
 *  GET   /by-sound-type      → by soundType           ?soundType=poem
 *  GET   /by-topic           → by topic               ?topicId=5
 *  GET   /album-of-memories  → album-of-memories only
 *  GET   /search/tag         → tag search             ?tag=کلاسیک
 *  GET   /search/keyword     → keyword search         ?keyword=شیعر
 *  GET   /search             → global search          ?q=هاوار
 *  GET   /topics             → SOUND topics for autocomplete
 *
 * ─── Matching rule for file parts ────────────────────────────────────────────
 *
 *  audioFiles[0]      ↔  dto.files[0]       uploaded binary wins over dto.fileUrl
 *  audioFiles[1]      ↔  dto.files[1]       etc.
 *  brochureFiles      flat list consumed globally across all files in index order
 *  attachmentFiles[0] ↔  dto.attachments[0] etc.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sound-tracks")
@RequiredArgsConstructor
@Tag(name = "Sound Tracks", description = "Bilingual sound tracks (SINGLE / MULTI) with files, brochures, attachments")
public class SoundTrackController {

    private final SoundTrackService          soundTrackService;
    private final PublishmentTopicRepository topicRepository;
    private final ObjectMapper               objectMapper;

    private final SiteContentService siteContentService;

    // Featured Patch
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody SiteContentDtos.FeaturedRequest request) {
        siteContentService.setSoundTrackFeatured(id, request);
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
        soundTrackService.reorder(request != null ? request.getOrderedIds() : null);
        return ResponseEntity.ok(ApiResponse.success(null, "Order updated"));
    }




    // =========================================================================
    // CREATE — multipart/form-data
    // =========================================================================

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Response>> create(
            @RequestPart("data")
            String dataJson,

            @RequestPart(value = "ckbCoverImage",    required = false)
            MultipartFile ckbCoverImage,

            @RequestPart(value = "kmrCoverImage",    required = false)
            MultipartFile kmrCoverImage,

            @RequestPart(value = "hoverCoverImage",  required = false)
            MultipartFile hoverCoverImage,

            @RequestPart(value = "audioFiles",       required = false)
            List<MultipartFile> audioFiles,

            @RequestPart(value = "brochureFiles",    required = false)
            List<MultipartFile> brochureFiles,

            @RequestPart(value = "attachmentFiles",  required = false)
            List<MultipartFile> attachmentFiles
    ) throws Exception {

        CreateRequest dto = objectMapper.readValue(dataJson, CreateRequest.class);

        log.info("POST /api/v1/sound-tracks | soundType={} state={} langs={} " +
                        "ckbCover={} kmrCover={} hoverCover={} " +
                        "audioFiles={} brochureFiles={} attachmentFiles={} fileDtos={}",
                dto.getSoundType(), dto.getTrackState(), dto.getContentLanguages(),
                hasFile(ckbCoverImage), hasFile(kmrCoverImage), hasFile(hoverCoverImage),
                countFiles(audioFiles), countFiles(brochureFiles), countFiles(attachmentFiles),
                dto.getFiles() != null ? dto.getFiles().size() : 0);

        Response created = soundTrackService.create(
                dto,
                ckbCoverImage, kmrCoverImage, hoverCoverImage,
                audioFiles, brochureFiles, attachmentFiles);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "SoundTrack created successfully"));
    }

    // =========================================================================
    // UPDATE — multipart/form-data
    //
    //  Only fields present in the data JSON are changed.
    //  To remove the topic:  set clearTopic = true inside data JSON.
    //  To replace all files: send files array in data JSON + matching audioFiles parts.
    //  To leave files unchanged: omit both dto.files and audioFiles entirely.
    // =========================================================================

    @PutMapping(
            value    = "/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Response>> update(
            @PathVariable Long id,

            @RequestPart("data")
            String dataJson,

            @RequestPart(value = "ckbCoverImage",    required = false)
            MultipartFile ckbCoverImage,

            @RequestPart(value = "kmrCoverImage",    required = false)
            MultipartFile kmrCoverImage,

            @RequestPart(value = "hoverCoverImage",  required = false)
            MultipartFile hoverCoverImage,

            @RequestPart(value = "audioFiles",       required = false)
            List<MultipartFile> audioFiles,

            @RequestPart(value = "brochureFiles",    required = false)
            List<MultipartFile> brochureFiles,

            @RequestPart(value = "attachmentFiles",  required = false)
            List<MultipartFile> attachmentFiles
    ) throws Exception {

        UpdateRequest dto = objectMapper.readValue(dataJson, UpdateRequest.class);

        log.info("PUT /api/v1/sound-tracks/{} | soundType={} state={} clearTopic={} " +
                        "ckbCover={} kmrCover={} hoverCover={} " +
                        "audioFiles={} brochureFiles={} attachmentFiles={} fileDtos={}",
                id, dto.getSoundType(), dto.getTrackState(), dto.isClearTopic(),
                hasFile(ckbCoverImage), hasFile(kmrCoverImage), hasFile(hoverCoverImage),
                countFiles(audioFiles), countFiles(brochureFiles), countFiles(attachmentFiles),
                dto.getFiles() != null ? dto.getFiles().size() : -1);

        Response updated = soundTrackService.update(
                id, dto,
                ckbCoverImage, kmrCoverImage, hoverCoverImage,
                audioFiles, brochureFiles, attachmentFiles);

        return ResponseEntity.ok(
                ApiResponse.success(updated, "SoundTrack updated successfully"));
    }

    @PostMapping(
            value = "/sound-reklam-video",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<SoundReklamVideoResponse>> createSoundReklamVideo(
            @RequestPart("videoFile") MultipartFile videoFile
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                soundTrackService.createSoundReklamVideo(videoFile),
                "Sound reklam video created successfully"
        ));
    }

    @GetMapping(value = "/sound-reklam-video", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SoundReklamVideoResponse>> getSoundReklamVideo() {
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getSoundReklamVideo(),
                "Sound reklam video fetched successfully"
        ));
    }

    @PatchMapping(
            value = "/sound-reklam-video",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<SoundReklamVideoResponse>> updateSoundReklamVideo(
            @RequestPart("videoFile") MultipartFile videoFile
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.updateSoundReklamVideo(videoFile),
                "Sound reklam video updated successfully"
        ));
    }

    @DeleteMapping(value = "/sound-reklam-video", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> deleteSoundReklamVideo() {
        soundTrackService.deleteSoundReklamVideo();
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // GET ALL
    // =========================================================================

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getAll(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/sound-tracks | page={} size={}", page, size);
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getAll(page, size),
                "SoundTracks fetched successfully"));
    }

    @GetMapping(value = "/featured", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getFeatured(page, size),
                "Featured sound tracks fetched successfully"));
    }

    // =========================================================================
    // GET BY ID
    // =========================================================================

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Response>> getById(@PathVariable Long id) {
        log.info("GET /api/v1/sound-tracks/{}", id);
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getById(id),
                "SoundTrack fetched successfully"));
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("DELETE /api/v1/sound-tracks/{}", id);
        soundTrackService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // FILTER — by TrackState  ?state=SINGLE | MULTI
    // =========================================================================

    @GetMapping(value = "/by-state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getByState(
            @RequestParam TrackState state,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/sound-tracks/by-state | state={} page={} size={}", state, page, size);
        if (state == null) throw Errors.soundValidation("soundTrack.state.required", Map.of("field", "state"));
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getByState(state, page, size),
                "SoundTracks by state fetched successfully"));
    }

    // =========================================================================
    // FILTER — by soundType  ?soundType=poem
    // =========================================================================

    @GetMapping(value = "/by-sound-type", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getBySoundType(
            @RequestParam(required = false) String soundType,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String query = soundType != null && !soundType.isBlank() ? soundType : type;
        log.info("GET /api/v1/sound-tracks/by-sound-type | soundType={} page={} size={}",
                query, page, size);
        if (query == null || query.isBlank()) throw Errors.soundValidation("soundTrack.soundType.required", Map.of("field", "soundType"));
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getBySoundType(query, page, size),
                "SoundTracks by sound type fetched successfully"));
    }

    // =========================================================================
    // FILTER — by Topic  ?topicId=5
    // =========================================================================

    @GetMapping(value = "/by-topic", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getByTopic(
            @RequestParam Long topicId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/sound-tracks/by-topic | topicId={} page={} size={}",
                topicId, page, size);
        if (topicId == null) throw Errors.soundValidation("error.validation", Map.of("field", "topicId"));
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getByTopic(topicId, page, size),
                "SoundTracks by topic fetched successfully"));
    }

    // =========================================================================
    // FILTER — Album of Memories
    // =========================================================================

    @GetMapping(value = "/album-of-memories", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> getAlbumOfMemories(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/sound-tracks/album-of-memories | page={} size={}", page, size);
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.getAlbumOfMemories(page, size),
                "Album of memories fetched successfully"));
    }

    // =========================================================================
    // SEARCH — by Tag  ?tag=کلاسیک
    // =========================================================================

    @GetMapping(value = "/search/tag", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> searchByTag(
            @RequestParam(required = false) String tag,
            @RequestParam(name = "value", required = false) String value,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String query = tag != null && !tag.isBlank() ? tag : value;
        log.info("GET /api/v1/sound-tracks/search/tag | tag={} page={} size={}", query, page, size);
        if (query == null || query.isBlank()) throw Errors.badRequest("tag.required", Map.of("field", "tag"));
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.searchByTag(query, page, size),
                "SoundTracks by tag fetched successfully"));
    }

    // =========================================================================
    // SEARCH — by Keyword  ?keyword=شیعر
    // =========================================================================

    @GetMapping(value = "/search/keyword", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> searchByKeyword(
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "value", required = false) String value,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String query = keyword != null && !keyword.isBlank() ? keyword : value;
        log.info("GET /api/v1/sound-tracks/search/keyword | keyword={} page={} size={}",
                query, page, size);
        if (query == null || query.isBlank()) throw Errors.badRequest("keyword.required", Map.of("field", "keyword"));
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.searchByKeyword(query, page, size),
                "SoundTracks by keyword fetched successfully"));
    }

    // =========================================================================
    // SEARCH — Global  ?q=هاوار
    // =========================================================================

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Page<Response>>> globalSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/sound-tracks/search | q={} page={} size={}", q, page, size);
        if (q == null || q.isBlank()) throw Errors.badRequest("keyword.required", Map.of("field", "q"));
        return ResponseEntity.ok(ApiResponse.success(
                soundTrackService.globalSearch(q, page, size),
                "SoundTracks global search fetched successfully"));
    }

    // =========================================================================
    // TOPICS — list SOUND topics for frontend autocomplete
    // =========================================================================

    @GetMapping(value = "/topics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopics() {
        List<PublishmentTopic> topics = topicRepository.findByEntityType("SOUND");
        List<Map<String, Object>> result = topics.stream()
                .map(t -> Map.<String, Object>of(
                        "id",      t.getId(),
                        "nameCkb", t.getNameCkb() != null ? t.getNameCkb() : "",
                        "nameKmr", t.getNameKmr() != null ? t.getNameKmr() : ""))
                .toList();
        return ResponseEntity.ok(
                ApiResponse.success(result, "SOUND topics fetched successfully"));
    }

    // =========================================================================
    // PRIVATE UTILS
    // =========================================================================

    private boolean hasFile(MultipartFile f) { return f != null && !f.isEmpty(); }

    private int countFiles(List<MultipartFile> files) {
        if (files == null) return 0;
        return (int) files.stream().filter(f -> f != null && !f.isEmpty()).count();
    }
}
