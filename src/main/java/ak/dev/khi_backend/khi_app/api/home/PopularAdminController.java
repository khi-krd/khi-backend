package ak.dev.khi_backend.khi_app.api.home;

import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.project.Project;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Admin control of the {@code isPopular} homepage flag.
 *
 * One endpoint covers every homepage entity so the dashboard can drive all
 * Popular switches through a single service. ADMIN/SUPER_ADMIN only — the
 * same guard the existing {@code /featured} toggles use.
 */
@RestController
@RequestMapping("/api/v1/popular")
@RequiredArgsConstructor
@Tag(name = "Popular Content", description = "Admin isPopular toggles + state")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class PopularAdminController {

    private final NewsRepository newsRepository;
    private final ProjectRepository projectRepository;
    private final VideoRepository videoRepository;
    private final SoundTrackRepository soundTrackRepository;
    private final WritingRepository writingRepository;
    private final ImageCollectionRepository imageCollectionRepository;

    /** Entity keys accepted by the dashboard: news, projects, videos, sounds, writings, collections. */
    @PatchMapping("/{entity}/{id}")
    @Operation(summary = "Toggle isPopular for a record")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable String entity,
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        Boolean popular = body.get("isPopular");
        if (popular == null) {
            throw new ResponseStatusException(BAD_REQUEST, "isPopular is required");
        }

        Object record = switch (entity) {
            case "news" -> {
                News n = newsRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "News not found"));
                n.setPopular(popular);
                yield newsRepository.save(n);
            }
            case "projects" -> {
                Project p = projectRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Project not found"));
                p.setPopular(popular);
                yield projectRepository.save(p);
            }
            case "videos" -> {
                Video v = videoRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Video not found"));
                v.setPopular(popular);
                yield videoRepository.save(v);
            }
            case "sounds" -> {
                SoundTrack s = soundTrackRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Sound track not found"));
                s.setPopular(popular);
                yield soundTrackRepository.save(s);
            }
            case "writings" -> {
                Writing w = writingRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Writing not found"));
                w.setPopular(popular);
                yield writingRepository.save(w);
            }
            case "collections" -> {
                ImageCollection c = imageCollectionRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Collection not found"));
                c.setPopular(popular);
                yield imageCollectionRepository.save(c);
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unknown entity: " + entity);
        };

        Long recordId = switch (record) {
            case News n -> n.getId();
            case Project p -> p.getId();
            case Video v -> v.getId();
            case SoundTrack s -> s.getId();
            case Writing w -> w.getId();
            case ImageCollection c -> c.getId();
            default -> null;
        };
        return ResponseEntity.ok(Map.of("entity", entity, "id", recordId, "isPopular", popular));
    }

    /** Popular ids per entity — lets the dashboard render switch state without DTO changes. */
    @GetMapping("/ids")
    @Operation(summary = "Ids of records currently flagged popular, per entity")
    public ResponseEntity<Map<String, List<Long>>> popularIds() {
        return ResponseEntity.ok(Map.of(
                "news", ids(newsRepository.findAll().stream().filter(News::isPopular).map(News::getId).toList()),
                "projects", ids(projectRepository.findAll().stream().filter(Project::isPopular).map(Project::getId).toList()),
                "videos", ids(videoRepository.findAll().stream().filter(Video::isPopular).map(Video::getId).toList()),
                "sounds", ids(soundTrackRepository.findAll().stream().filter(SoundTrack::isPopular).map(SoundTrack::getId).toList()),
                "writings", ids(writingRepository.findAll().stream().filter(Writing::isPopular).map(Writing::getId).toList()),
                "collections", ids(imageCollectionRepository.findAll().stream().filter(ImageCollection::isPopular).map(ImageCollection::getId).toList())));
    }

    private static List<Long> ids(List<Long> list) {
        return list;
    }
}
