package ak.dev.khi_backend.khi_app.api.publishment.topic;


import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.service.publishment.topic.PublishmentTopicService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PublishmentTopicController
 *
 * Base path: /api/v1/topics
 *
 * ─── Endpoints ────────────────────────────────────────────────────────────────
 *
 *  GET    /api/v1/topics/{entityType}          → list all topics for a type
 *  GET    /api/v1/topics/{entityType}/{id}      → get single topic
 *  POST   /api/v1/topics/{entityType}           → create topic
 *  PUT    /api/v1/topics/{id}                   → update topic
 *  DELETE /api/v1/topics/{id}                   → delete topic
 *
 * entityType values:  VIDEO | SOUND | IMAGE | WRITING
 *
 * ─── Also wired into sub-resource shortcuts ───────────────────────────────────
 *  The SoundTrackController exposes  GET /api/v1/soundtracks/topics
 *  The VideoController exposes       GET /api/v1/videos/topics
 *  Both delegate to this service so there is one source of truth.
 */
@RestController
@RequestMapping("/api/v1/topics")
@RequiredArgsConstructor
@Tag(name = "Publishment Topics", description = "Central VIDEO / SOUND / IMAGE / WRITING taxonomy")
public class PublishmentTopicController {

    private final PublishmentTopicService topicService;

    // ─── GET ALL BY TYPE ──────────────────────────────────────────────────────

    /**
     * GET /api/v1/topics/{entityType}
     * e.g. GET /api/v1/topics/SOUND
     *      GET /api/v1/topics/VIDEO
     */
    @GetMapping("/{entityType}")
    public ResponseEntity<List<PublishmentTopic>> getAllByType(
            @PathVariable String entityType
    ) {
        return ResponseEntity.ok(topicService.getAllByType(entityType));
    }

    // ─── GET ONE ──────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/topics/{entityType}/{id}
     */
    @GetMapping("/{entityType}/{id}")
    public ResponseEntity<PublishmentTopic> getById(
            @PathVariable String entityType,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(topicService.getById(id));
    }

    // ─── CREATE ───────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/topics/{entityType}
     * Body: { "nameCkb": "...", "nameKmr": "..." }
     */
    @PostMapping("/{entityType}")
    public ResponseEntity<PublishmentTopic> create(
            @PathVariable String entityType,
            @RequestBody(required = false) TopicRequest request
    ) {
        PublishmentTopic created = topicService.create(
                entityType,
                request.nameCkb(),
                request.nameKmr()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    /**
     * PUT /api/v1/topics/{id}
     * Body: { "nameCkb": "...", "nameKmr": "..." }
     */
    @PutMapping("/{id}")
    public ResponseEntity<PublishmentTopic> update(
            @PathVariable Long id,
            @RequestBody(required = false) TopicRequest request
    ) {
        PublishmentTopic updated = topicService.update(id, request.nameCkb(), request.nameKmr());
        return ResponseEntity.ok(updated);
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    /**
     * DELETE /api/v1/topics/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        topicService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Inner record for request body ────────────────────────────────────────

    public record TopicRequest(String nameCkb, String nameKmr) {}
}