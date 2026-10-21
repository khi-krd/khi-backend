package ak.dev.khi_backend.khi_app.api.media;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.media.MediaDtos.UploadResponse;
import ak.dev.khi_backend.khi_app.service.media.MediaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * MediaController — shared S3 upload endpoints used by every Tiptap editor
 * across the platform (News, Projects, About, Services, Sound, Video, Image,
 * Writing).
 *
 * Flow used by the frontend:
 *   1. User drops an image / audio / video into the Tiptap editor.
 *   2. Frontend uploads via POST /api/v1/media/upload.
 *   3. The returned {@code fileUrl} is inserted into the editor HTML.
 *   4. Final HTML is stored in the entity's bilingual {@code description} /
 *      {@code body} TEXT column.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
@Tag(name = "Media", description = "Shared S3 upload pipeline used by every Tiptap editor")
public class MediaController {

    private final MediaService mediaService;
    private final ak.dev.khi_backend.khi_app.service.media.MediaMaintenanceService maintenanceService;

    /**
     * Start a background library maintenance run (admin only via the
     * /api/v1/media/** security rule). Body: {"tasks": ["faststart",
     * "cache", "metadata"]} — omit for all three.
     */
    @PostMapping(value = "/maintenance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> startMaintenance(
            @RequestBody(required = false) java.util.Map<String, java.util.List<String>> body
    ) {
        java.util.List<String> tasks = body == null ? null : body.get("tasks");
        java.util.Set<String> selected = tasks == null || tasks.isEmpty()
                ? java.util.Set.of("faststart", "cache", "metadata")
                : new java.util.HashSet<>(tasks);
        return ResponseEntity.accepted().body(maintenanceService.start(selected));
    }

    @GetMapping(value = "/maintenance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> maintenanceStatus() {
        return ResponseEntity.ok(maintenanceService.status());
    }

    /**
     * Upload a single media file to S3.
     *
     * Multipart parts:
     *   file  — required, the file to upload (any media type)
     *   type  — optional folder hint: "image" | "audio" | "video" |
     *           "document" | "gallery". Default "image".
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadResponse>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "type", required = false) String type
    ) throws IOException {
        String resolved = (type != null && !type.isBlank()) ? type : "image";
        UploadResponse response = mediaService.upload(file, resolved);
        return ResponseEntity.ok(
                ApiResponse.success(response, "Media uploaded successfully"));
    }

    /**
     * Upload multiple files in a single request. Useful for Tiptap drag-and-drop
     * of a folder, or for the editor's "insert multiple images" flow.
     */
    @PostMapping(value = "/upload/multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<List<UploadResponse>>> uploadMultiple(
            @RequestPart("files") List<MultipartFile> files,
            @RequestPart(value = "type", required = false) String type
    ) {
        String resolved = (type != null && !type.isBlank()) ? type : "image";
        List<UploadResponse> responses = mediaService.uploadMultiple(files, resolved);
        return ResponseEntity.ok(
                ApiResponse.success(responses, "Media uploaded successfully"));
    }

    /**
     * Delete a previously uploaded media file from S3 by URL.
     * Reserved for future S3-orphan cleanup; current Tiptap content rarely
     * needs to delete individual assets.
     */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> delete(@RequestParam("fileUrl") String fileUrl) {
        mediaService.delete(fileUrl);
        return ResponseEntity.ok(ApiResponse.success(null, "Media deleted successfully"));
    }
}
