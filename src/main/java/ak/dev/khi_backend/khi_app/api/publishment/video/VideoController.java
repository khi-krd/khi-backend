package ak.dev.khi_backend.khi_app.api.publishment.video;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.video.VideoDTO;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
import ak.dev.khi_backend.khi_app.service.publishment.video.VideoService;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * VideoController — REST API for Video publishments.
 *
 * ─── Multipart upload strategy ────────────────────────────────────────────────
 *
 *  Every write endpoint (POST / PUT) consumes multipart/form-data with the
 *  following named parts:
 *
 *   data          (required) — JSON VideoDTO serialised as a string
 *   ckbCoverImage (optional) — cover image for the CKB (Sorani) dialect
 *   kmrCoverImage (optional) — cover image for the KMR (Kurmanji) dialect
 *   hoverImage    (optional) — hover/thumbnail cover image
 *   videoFiles    (optional) — one or more video files:
 *
 *      FILM type       → EVERY uploaded file is kept as a source in
 *                        `videoSources[]`. videoFiles[i] maps to videoSources[i]
 *                        by index; extra files are appended. The first source is
 *                        `main: true` by default (or whichever `videoSources[i]`
 *                        carries `main: true`). The main source is mirrored onto
 *                        the legacy sourceUrl / sourceExternalUrl / sourceEmbedUrl.
 *      VIDEO_CLIP type → videoFiles[i] maps to videoClipItems[i] by index.
 *                        Each uploaded file sets that clip's url and clears
 *                        its externalUrl / embedUrl.
 *
 *  Uploaded files take priority over any URL already present in the JSON.
 *  Omit a videoFiles slot (or send no videoFiles at all) to keep the existing
 *  URL for that clip / source on update, or to supply a URL via the JSON data
 *  part instead.
 *
 * ─── Topic handling ────────────────────────────────────────────────────────────
 *  Topics are shared across videos.  You can:
 *    - Assign an existing topic by ID  → set "topicId" in the JSON
 *    - Create and assign in one step   → set "newTopic": { nameCkb, nameKmr }
 *    - Remove the topic on update      → set "clearTopic": true
 *  Dedicated CRUD for topics: GET/POST/DELETE /api/v1/videos/topics
 */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
@Tag(name = "Videos", description = "Bilingual video publishments — FILM and VIDEO_CLIP types with direct multipart file upload")
public class VideoController {

    private final VideoService videoService;
    private final ObjectMapper objectMapper;

    private final SiteContentService siteContentService;

    @Operation(summary = "Mark / unmark a video as featured (ADMIN only)")
    @PatchMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(
            @PathVariable Long id,
            @RequestBody SiteContentDtos.FeaturedRequest request) {
        siteContentService.setVideoFeatured(id, request);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // FILM REKLAM VIDEO — homepage Film section background
    // =========================================================================
    //
    // The Film counterpart of GET /api/v1/sound-tracks/sound-reklam-video. A
    // singleton: one video site-wide, so no id in the path. It plays muted and
    // looping behind the film cards and is unrelated to any Video row.
    //
    // Dashboard flow: GET on screen open — 200 means show replace/remove, 404
    // means show upload. The first upload is POST, every one after that is PATCH.

    /**
     * Upload the Film section background video — first time only.
     *
     * <p>Fails with 400 when a video already exists; use {@code PATCH} to replace it.</p>
     */
    @Operation(summary = "Upload the Film section background video (EMPLOYEE, ADMIN, SUPER_ADMIN)")
    @PostMapping(
            value = "/film-reklam-video",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<VideoDTO.FilmReklamVideoResponse>> createFilmReklamVideo(
            @Parameter(description = "The video file — content type must start with video/")
            @RequestPart("videoFile") MultipartFile videoFile
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                videoService.createFilmReklamVideo(videoFile),
                "Film reklam video created successfully"
        ));
    }

    /**
     * Read the Film section background video.
     *
     * <p>Returns 404 when nothing has been uploaded. That is the normal empty state —
     * the website hides the background and renders the Film section as usual.</p>
     */
    @Operation(summary = "Get the Film section background video (public)")
    @GetMapping(value = "/film-reklam-video", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<VideoDTO.FilmReklamVideoResponse>> getFilmReklamVideo() {
        return ResponseEntity.ok(ApiResponse.success(
                videoService.getFilmReklamVideo(),
                "Film reklam video fetched successfully"
        ));
    }

    /**
     * Replace the Film section background video file.
     *
     * <p>A full replacement despite the PATCH verb — there is no other field to patch.
     * Fails with 404 when no video exists yet; fall back to {@code POST}.</p>
     */
    @Operation(summary = "Replace the Film section background video (EMPLOYEE, ADMIN, SUPER_ADMIN)")
    @PatchMapping(
            value = "/film-reklam-video",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<VideoDTO.FilmReklamVideoResponse>> updateFilmReklamVideo(
            @Parameter(description = "The replacement video file — content type must start with video/")
            @RequestPart("videoFile") MultipartFile videoFile
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                videoService.updateFilmReklamVideo(videoFile),
                "Film reklam video updated successfully"
        ));
    }

    /**
     * Remove the Film section background video.
     *
     * <p>Not idempotent — deleting twice returns 404 the second time.</p>
     */
    @Operation(summary = "Delete the Film section background video (ADMIN, SUPER_ADMIN)")
    @DeleteMapping(value = "/film-reklam-video", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> deleteFilmReklamVideo() {
        videoService.deleteFilmReklamVideo();
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Create a new video (FILM or VIDEO_CLIP)",
        description = """
            Multipart request. Required part: `data` (JSON VideoDTO).
            Optional file parts:
            - `ckbCoverImage` / `kmrCoverImage` / `hoverImage` — cover images uploaded directly.
            - `videoFiles` — video files uploaded directly (repeat the part for each file):
                - FILM: EVERY file is stored in `videoSources[]`; the first is `main: true`.
                - VIDEO_CLIP: `videoFiles[i]` maps to `videoClipItems[i]` by index.
            Files take priority over any URL in the JSON `data` part.
            """
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoDTO> addVideo(
            @Parameter(description = "JSON-serialised VideoDTO") @RequestPart("data") String dtoJson,

            @Parameter(description = "Cover image — CKB dialect") @RequestPart(value = "ckbCoverImage", required = false) MultipartFile ckbCoverImage,
            @Parameter(description = "Cover image — KMR dialect") @RequestPart(value = "kmrCoverImage", required = false) MultipartFile kmrCoverImage,
            @Parameter(description = "Hover/thumbnail cover image") @RequestPart(value = "hoverImage", required = false) MultipartFile hoverImage,

            @Parameter(description = "Video file(s). FILM: every file is stored in videoSources[]; first is main. VIDEO_CLIP: index i = clip i.")
            @RequestPart(value = "videoFiles", required = false) List<MultipartFile> videoFiles
    ) throws Exception {
        VideoDTO dto = objectMapper.readValue(dtoJson, VideoDTO.class);
        VideoDTO created = videoService.addVideo(dto, ckbCoverImage, kmrCoverImage, hoverImage, videoFiles);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "List all video topics")
    @GetMapping("/topics")
    public ResponseEntity<List<VideoDTO.TopicView>> getVideoTopics() {
        return ResponseEntity.ok(videoService.getTopics());
    }

    @Operation(summary = "Create a video topic")
    @PostMapping("/topics")
    public ResponseEntity<VideoDTO.TopicView> createVideoTopic(@RequestParam(required = false) String nameCkb,
                                                               @RequestParam(required = false) String nameKmr) {
        return ResponseEntity.status(HttpStatus.CREATED).body(videoService.createTopic(nameCkb, nameKmr));
    }

    @Operation(summary = "Delete a video topic (un-links all videos first)")
    @DeleteMapping("/topics/{topicId}")
    public ResponseEntity<Void> deleteVideoTopic(@PathVariable Long topicId) {
        videoService.deleteTopic(topicId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "List / filter videos (paged)",
        description = """
            Filter params (all optional, combinable):
            - `videoType`  = FILM | VIDEO_CLIP
            - `memories`   = true | false  (only meaningful when videoType=VIDEO_CLIP)
            - `topicId`    = filter by topic FK
            - `page` / `size` for pagination (max size 100)
            """
    )
    @GetMapping
    public ResponseEntity<Page<VideoDTO>> getAllVideos(
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false, name = "memories") Boolean albumOfMemories,
            @RequestParam(required = false) Long topicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(videoService.getVideoListing(
                videoType, albumOfMemories, topicId, page, size));
    }

    @Operation(summary = "List featured videos ordered by featuredOrder ASC")
    @GetMapping("/featured")
    public ResponseEntity<Page<VideoDTO>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(videoService.getFeatured(page, size));
    }

    @Operation(summary = "Get a single video by ID (full detail with clip items)")
    @GetMapping("/{id}")
    public ResponseEntity<VideoDTO> getVideoById(@PathVariable Long id) {
        return ResponseEntity.ok(videoService.getVideoById(id));
    }

    @Operation(summary = "Search videos by tag (CKB or KMR, partial match, paged)")
    @GetMapping("/search/tag")
    public ResponseEntity<Page<VideoDTO>> searchByTag(
            @RequestParam("value") String value,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(videoService.searchByTag(value, page, size));
    }

    @Operation(summary = "Search videos by keyword across titles, descriptions, directors, and keyword collections (paged)")
    @GetMapping("/search/keyword")
    public ResponseEntity<Page<VideoDTO>> searchByKeyword(
            @RequestParam("value") String value,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(videoService.searchByKeyword(value, page, size));
    }

    @Operation(
        summary = "Update an existing video",
        description = """
            Partial update — null fields in the JSON are ignored (not cleared).
            Same multipart rules as POST:
            - FILM: uploading `videoFiles` (or sending `videoSources`) rebuilds the
              whole source list; omit both to keep the existing sources untouched.
            - `videoFiles[i]` replaces clip i's source; omit to keep the existing source.
            - On VIDEO_CLIP updates, each clip item in the JSON must carry its persisted `id`.
            - To remove the topic: set `clearTopic: true` in the JSON.
            """
    )
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoDTO> updateVideo(
            @PathVariable Long id,
            @Parameter(description = "JSON-serialised VideoDTO") @RequestPart("data") String dtoJson,

            @Parameter(description = "Replacement cover image — CKB dialect") @RequestPart(value = "ckbCoverImage", required = false) MultipartFile ckbCoverImage,
            @Parameter(description = "Replacement cover image — KMR dialect") @RequestPart(value = "kmrCoverImage", required = false) MultipartFile kmrCoverImage,
            @Parameter(description = "Replacement hover/thumbnail cover image") @RequestPart(value = "hoverImage", required = false) MultipartFile hoverImage,

            @Parameter(description = "Replacement video file(s). FILM: rebuilds videoSources[] (first is main). VIDEO_CLIP: index i = clip i. Omit to keep existing sources.")
            @RequestPart(value = "videoFiles", required = false) List<MultipartFile> videoFiles
    ) throws Exception {
        VideoDTO dto = objectMapper.readValue(dtoJson, VideoDTO.class);
        VideoDTO updated = videoService.updateVideo(id, dto, ckbCoverImage, kmrCoverImage, hoverImage, videoFiles);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete a video (idempotent — missing ID is a no-op)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVideo(@PathVariable Long id) {
        videoService.deleteVideo(id);
        return ResponseEntity.noContent().build();
    }
}
