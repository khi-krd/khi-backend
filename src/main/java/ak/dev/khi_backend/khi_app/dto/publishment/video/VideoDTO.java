package ak.dev.khi_backend.khi_app.dto.publishment.video;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VideoDTO {

    // ─── Identity ─────────────────────────────────────────────────────────────
    private Long id;

    // ─── Cover ────────────────────────────────────────────────────────────────
    private String ckbCoverUrl;                       //   but field says "ckb"

    private String kmrCoverUrl;                       //   but field says "kmr"

    private String hoverCoverUrl;

    /**
     * Hero picture for the homepage carousel. Read-only — written through
     * {@code PATCH /api/v1/videos/{id}/featured}, not by saving the video.
     */
    private String featureImageUrl;

    // ─── Video Type & Album Flag ──────────────────────────────────────────────
    private VideoType videoType;
    private Boolean albumOfMemories;

    // ─── Topic (FK) ───────────────────────────────────────────────────────────

    /**
     * Option A: assign an existing topic by ID.
     */
    private Long topicId;

    /**
     * Option B: create a brand-new topic inline and assign it automatically.
     * If both topicId and newTopic are supplied, topicId takes precedence.
     * Ignored on response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private InlineTopicRequest newTopic;

    /** On update only — set true to clear the topic relation. */
    @Builder.Default
    private boolean clearTopic = false;

    /** Response only — CKB name of the assigned topic. */
    private String topicNameCkb;

    /** Response only — KMR name of the assigned topic. */
    private String topicNameKmr;

    // ─── Languages ────────────────────────────────────────────────────────────
    private Set<Language> contentLanguages;

    // ─── Bilingual Content ────────────────────────────────────────────────────
    private VideoContentDTO ckbContent;
    private VideoContentDTO kmrContent;

    // ─── Video Sources (FILM only) ────────────────────────────────────────────

    /**
     * All FILM sources, in order. Every uploaded video file appears here and
     * exactly one carries {@code main: true}. On input you may send URL-based
     * sources here; uploaded `videoFiles` are matched by index and take priority.
     */
    private List<VideoSourceDTO> videoSources;

    /** MAIN source mirror (backward compatible) — equals the `main` entry of {@link #videoSources}. */
    private String sourceUrl;
    private String sourceExternalUrl;
    private String sourceEmbedUrl;

    // ─── Clip Items (VIDEO_CLIP only) ─────────────────────────────────────────
    private List<VideoClipItemDTO> videoClipItems;
    private List<CastMemberDTO> castMembers;
    private List<HighlightClipDTO> highlightClips;

    // ─── Common Metadata ──────────────────────────────────────────────────────
    private String fileFormat;
    private Integer durationSeconds;
    private LocalDate publishmentDate;
    private String resolution;
    private Double fileSizeMb;

    // ─── Tags & Keywords ──────────────────────────────────────────────────────
    private Set<String> tagsCkb;
    private Set<String> tagsKmr;
    private Set<String> keywordsCkb;
    private Set<String> keywordsKmr;

    // ─── Timestamps ───────────────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // =========================================================================
    //  NESTED DTOs
    // =========================================================================

    /**
     * Inline topic creation payload.
     * Provide either nameCkb or nameKmr (or both).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class InlineTopicRequest {
        private String nameCkb;
        private String nameKmr;
    }

    /**
     * Lightweight topic view returned in topic-list responses.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class TopicView {
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private LocalDateTime createdAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class VideoContentDTO {
        private String title;
        private String description;
        private String location;
        private String director;
        private String producer;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class VideoClipItemDTO {
        private Long id;
        private String url;
        private String externalUrl;
        private String embedUrl;
        private Integer clipNumber;
        private Integer durationSeconds;
        private String resolution;
        private String fileFormat;
        private Double fileSizeMb;
        private String titleCkb;
        private String titleKmr;
        private String descriptionCkb;
        private String descriptionKmr;
    }

    /**
     * One FILM video source. On the response, `main: true` marks the primary
     * file (the first one added by default). `durationSeconds`/`label` are optional.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class VideoSourceDTO {
        private String url;
        private String externalUrl;
        private String embedUrl;
        private Boolean main;
        private String label;
        private Integer durationSeconds;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CastMemberDTO {
        private String nameCkb;
        private String nameKmr;
        private String roleCkb;
        private String roleKmr;
        private String imageUrl;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HighlightClipDTO {
        private String titleCkb;
        private String titleKmr;
        private String url;
        private String embedUrl;
        private Integer durationSeconds;
    }

    // =========================================================================
    // FILM REKLAM VIDEO RESPONSE
    // =========================================================================
    //
    // The homepage Film section background. A singleton, unrelated to any Video
    // row — see FilmReklamVideo. Same shape as SoundReklamVideoResponse so the
    // dashboard can reuse one component for both sections.

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class FilmReklamVideoResponse {
        private Long id;
        private String videoUrl;
        private long sizeBytes;
        private String mimeType;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
