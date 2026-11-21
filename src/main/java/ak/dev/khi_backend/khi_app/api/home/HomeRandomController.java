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

/**
 * Public random-content feed — the same published section lists the popular
 * feed draws from, shuffled per request. Read-only and unauthenticated like
 * every other public listing.
 */
@RestController
@RequestMapping("/api/v1/home/random")
@RequiredArgsConstructor
@Tag(name = "Home Random", description = "Randomly selected homepage section feeds")
public class HomeRandomController {

    private final HomePopularService homePopularService;

    @GetMapping("/{section}")
    @Operation(summary = "Randomly selected items for a homepage section")
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
        return ResponseEntity.ok(homePopularService.random(section, type, limit));
    }
}
