package ak.dev.khi_backend.khi_app.dto.publishment.sound;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.AttachmentType;
import ak.dev.khi_backend.khi_app.enums.publishment.AudioChannel;
import ak.dev.khi_backend.khi_app.enums.publishment.FileType;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class SoundTrackDtos {

    private SoundTrackDtos() {}

    // =========================================================================
    // LANGUAGE CONTENT DTO
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LanguageContentDto {

        @Size(max = 200)
        private String title;

        @Size(max = 4000)
        private String description;


    }

    // =========================================================================
    // BILINGUAL SET DTO
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BilingualSet {
        private Set<String> ckb;
        private Set<String> kmr;
    }

    // =========================================================================
    // INLINE TOPIC
    // =========================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class InlineTopicRequest {
        private String nameCkb;
        private String nameKmr;
    }

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

    // =========================================================================
    // BROCHURE DTOs
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(value = "brochureOrder")
    public static class BrochureRequest {

        /** Existing brochure ID used to preserve its source during update. */
        private Long id;

        // FIX: Removed @NotBlank — imageUrl may be null when the binary is
        // supplied via the brochureFiles multipart part instead of a URL.
        // The service already skips entries where imageUrl is blank after upload.
        @Size(max = 1200)
        private String imageUrl;

        @Size(max = 300)
        private String caption;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BrochureResponse {
        private Long id;
        private String imageUrl;
        private String caption;
        private Integer brochureOrder;
    }

    // =========================================================================
    // ATTACHMENT DTOs
    // =========================================================================

    /**
     * Optional supplementary file attached to a SoundTrack.
     * e.g. PDF booklets, promo videos, lyric sheets.
     */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(value = "attachmentOrder")
    public static class AttachmentRequest {

        /** Existing attachment ID used to preserve its source during update. */
        private Long id;

        // FIX: Removed @NotBlank — fileUrl may be null when binary is
        // supplied via the attachmentFiles multipart part.
        // The service skips attachments where the resolved fileUrl is blank.
        @Size(max = 1200)
        private String fileUrl;

        @Size(max = 300)
        private String title;

        // FIX: Removed @NotNull — the service now defaults to AttachmentType.OTHER
        // when this field is absent, so a missing value no longer triggers a
        // ConstraintViolationException → 500.
        private AttachmentType attachmentType;

        /** File size in bytes. 0 when unknown. */
        private long sizeBytes;

        @Size(max = 100)
        private String mimeType;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class AttachmentResponse {
        private Long id;
        private String fileUrl;
        private String title;
        private AttachmentType attachmentType;
        private long sizeBytes;
        private String mimeType;
        private Integer attachmentOrder;
    }

    // =========================================================================
    // FILE CREATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(value = "durationMinutes")
    public static class FileCreateRequest {

        /** Existing soundtrack-file ID used for ID-aware update merging. */
        private Long id;

        // ── Locations ─────────────────────────────────────────────────────────
        @Size(max = 1200)
        private String fileUrl;

        private String externalUrl;
        private String embedUrl;

        // ── Identity ──────────────────────────────────────────────────────────
        @Size(max = 300)
        private String title;

        @NotNull(message = "fileType is required")
        private FileType fileType;

        private Integer publishmentYear;

        // ── Technical metadata ────────────────────────────────────────────────
        private long sizeBytes;
        private long durationSeconds;

        @Size(max = 50)
        private String bitRate;

        @Size(max = 50)
        private String sampleRate;

        private AudioChannel audioChannel;

        // ── Content / style ───────────────────────────────────────────────────
        @Size(max = 150)
        private String form;

        @Size(max = 100)
        private String genre;

        @Size(max = 500)
        private String recordingVenue;

        private Integer sortOrder;

        // ── Brochures ─────────────────────────────────────────────────────────
        private List<BrochureRequest> brochures;
    }

    // =========================================================================
    // FILE RESPONSE
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class FileResponse {

        private Long id;

        private String fileUrl;
        private String externalUrl;
        private String embedUrl;

        private String title;
        private FileType fileType;
        private Integer publishmentYear;

        private long sizeBytes;
        private long durationSeconds;
        private double durationMinutes;

        private String bitRate;
        private String sampleRate;
        private AudioChannel audioChannel;

        private String form;
        private String genre;
        private String recordingVenue;

        private List<BrochureResponse> brochures;
    }

    // =========================================================================
    // SOUNDTRACK CREATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        // ── Core ──────────────────────────────────────────────────────────────
        @NotBlank(message = "soundType is required")
        @Size(max = 100)
        private String soundType;

        @NotNull(message = "trackState is required")
        private TrackState trackState;

        private Boolean albumOfMemories;

        // ── Cover URLs (fallback when no multipart image is supplied) ─────────
        @Size(max = 1200)
        private String ckbCoverUrl;

        @Size(max = 1200)
        private String kmrCoverUrl;

        @Size(max = 1200)
        private String hoverCoverUrl;

        // ── Topic ─────────────────────────────────────────────────────────────
        private Long topicId;
        private InlineTopicRequest newTopic;

        // ── Languages ─────────────────────────────────────────────────────────
        @NotNull
        @NotEmpty(message = "At least one content language is required")
        private Set<Language> contentLanguages;

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        // ── Location ──────────────────────────────────────────────────────────
        private Set<String> locations;

        // ── People ────────────────────────────────────────────────────────────

        /**
         * Single reader / performer for the track.
         */
        @Size(max = 255)
        private String reader;

        /**
         * One or more directors.
         */
        private Set<String> directors;

        // ── Terms / Dialect ───────────────────────────────────────────────────
        @Size(max = 200)
        private String terms;

        // ── Institute ─────────────────────────────────────────────────────────
        @JsonProperty("thisProjectOfInstitute")
        private boolean thisProjectOfInstitute;

        // ── Tags & Keywords ───────────────────────────────────────────────────
        private BilingualSet tags;
        private BilingualSet keywords;

        // ── Audio Files ───────────────────────────────────────────────────────
        private List<FileCreateRequest> files;

        // ── Multi-Album Fields ────────────────────────────────────────────────
        @Size(max = 300)
        private String albumName;

        private Integer publishmentYear;
        private Integer cdNumber;
        private Integer totalTracks;

        /**
         * Optional supplementary attachments (PDF booklets, promo videos …).
         * Available for both SINGLE and MULTI tracks.
         */
        private List<AttachmentRequest> attachments;
    }

    // =========================================================================
    // SOUNDTRACK UPDATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        // ── Core ──────────────────────────────────────────────────────────────
        @Size(max = 100)
        private String soundType;

        private TrackState trackState;
        private Boolean albumOfMemories;

        // ── Cover URLs (multipart image wins when both are supplied) ──────────
        @Size(max = 1200)
        private String ckbCoverUrl;

        @Size(max = 1200)
        private String kmrCoverUrl;

        @Size(max = 1200)
        private String hoverCoverUrl;

        // ── Topic ─────────────────────────────────────────────────────────────
        private Long topicId;
        private InlineTopicRequest newTopic;
        private boolean clearTopic;

        // ── Languages ─────────────────────────────────────────────────────────
        private Set<Language> contentLanguages;
        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        // ── Location ──────────────────────────────────────────────────────────
        private Set<String> locations;

        // ── People ────────────────────────────────────────────────────────────

        /**
         * Single reader / performer. Pass null to leave unchanged.
         * Pass empty string "" to clear the reader.
         */
        @Size(max = 255)
        private String reader;

        private Set<String> directors;

        // ── Terms / Dialect ───────────────────────────────────────────────────
        @Size(max = 200)
        private String terms;

        // ── Institute ─────────────────────────────────────────────────────────
        @JsonProperty("thisProjectOfInstitute")
        private Boolean thisProjectOfInstitute;

        // ── Tags & Keywords ───────────────────────────────────────────────────
        private BilingualSet tags;
        private BilingualSet keywords;

        // ── Audio Files ───────────────────────────────────────────────────────
        private List<FileCreateRequest> files;

        // ── Multi-Album Fields ────────────────────────────────────────────────
        @Size(max = 300)
        private String albumName;

        private Integer publishmentYear;
        private Integer cdNumber;
        private Integer totalTracks;

        /**
         * Optional supplementary attachments.
         * Available for both SINGLE and MULTI tracks.
         */
        private List<AttachmentRequest> attachments;
    }

    // =========================================================================
    // SOUND REKLAM VIDEO RESPONSE
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SoundReklamVideoResponse {
        private Long id;
        private String videoUrl;
        private long sizeBytes;
        private String mimeType;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // =========================================================================
    // SOUNDTRACK RESPONSE
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class Response {

        private Long id;

        private String ckbCoverUrl;
        private String kmrCoverUrl;
        private String hoverCoverUrl;
        /** Hero picture for the homepage carousel; written via the featured PATCH. */
        private String featureImageUrl;

        private String soundType;
        private TrackState trackState;
        private Boolean albumOfMemories;

        private Long topicId;
        private String topicNameCkb;
        private String topicNameKmr;

        private Set<Language> contentLanguages;
        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        private Set<String> locations;

        /** Single reader / performer. */
        private String reader;

        private Set<String> directors;

        private String terms;

        @JsonProperty("thisProjectOfInstitute")
        private Boolean thisProjectOfInstitute;

        private BilingualSet tags;
        private BilingualSet keywords;

        private List<FileResponse> files;

        private long totalDurationSeconds;
        private long totalSizeBytes;

        private String albumName;
        private Integer publishmentYear;
        private Integer cdNumber;
        private Integer totalTracks;

        /** Optional supplementary attachments. */
        private List<AttachmentResponse> attachments;

        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
