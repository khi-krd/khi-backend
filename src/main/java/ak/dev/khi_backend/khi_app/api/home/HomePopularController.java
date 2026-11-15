package ak.dev.khi_backend.khi_app.api.home;

import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
import ak.dev.khi_backend.khi_app.service.home.HomePopularService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public homepage popular-content feed.
 *
 * Each section keeps the exact item limit the homepage already uses; the
 * service prioritises {@code isPopular} records with a rotating window and
 * fills the rest from the regular newest-first ordering.
 */
@RestController
@RequestMapping("/api/v1/home/popular")
@RequiredArgsConstructor
@Tag(name = "Home Popular", description = "Popular-first homepage section feeds")
public class HomePopularController {

    private final HomePopularService homePopularService;

    @GetMapping("/{section}")
    @Operation(summary = "Popular-first items for a homepage section")
    public ResponseEntity<List<?>> getSection(
            @PathVariable String section,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String videoType) {
        VideoType type = null;
        if (videoType != null && !videoType.isBlank()) {
            try {
                type = VideoType.valueOf(videoType.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                type = null;
            }
        }
        return ResponseEntity.ok(homePopularService.section(section, type, limit));
    }

    @GetMapping("/sections")
    @Operation(summary = "List supported homepage section keys")
    public ResponseEntity<Map<String, Object>> sections() {
        return ResponseEntity.ok(Map.of(
                "sections", List.of("news", "projects", "writings", "sounds", "collections", "videos"),
                "videoTypes", List.of("FILM", "VIDEO_CLIP")));
    }
}
